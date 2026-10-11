package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.repository.SettingsRepository
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 海报文件夹的读取、匹配与增删。
 *
 * 文件夹是**真的**临时目录（`Files.createTempDirectory`），库是真的 Room 库 —— ViewModel 只通过
 * `SettingsRepository` + `LibraryIndexRepository` 拿数据，所以一个真实 DataStore 加一个真实索引就
 * 把这条链跑通了，没有任何桩。
 *
 * 有几处只能这样测：
 *
 * * **`posters == null` 与空列表的区别**，正是移植时要守住的那条语义（Android 是 SAF 授权失效给的
 *   `null`，桌面是 `File.listFiles()` 的 `null`）。两者在界面上显示不同文案，所以两种情形都要造出来。
 * * **匹配是按文件名认人**，且大小写不敏感（`normalizeName` 会 NFKC + lowercase），所以文件用
 *   `aria.jpeg` 这种小写名，而库里存的是 `Aria`。
 * * **`refresh()` 只动 revision**，海报文件的内容是外部工具改的，reader 只能靠这条 revision 重新读。
 */
class ArtistPosterFoldersViewModelTest {

    private lateinit var fixture: LibraryBrowseFixture
    private lateinit var posterDir: File
    private lateinit var viewModel: ArtistPosterFoldersViewModel
    private val collectors = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        fixture = LibraryBrowseFixture()
        posterDir = Files.createTempDirectory("lyrico-posters").toFile()
        viewModel = ArtistPosterFoldersViewModel(
            settings = fixture.settings,
            libraryIndexRepository = fixture.index,
        )
        collectors.keepCollecting(viewModel.uiState)
    }

    @AfterTest
    fun tearDown() {
        collectors.cancel()
        fixture.close()
        Dispatchers.resetMain()
        posterDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ seeding

    /** Three artists, so "matched" cannot be satisfied by matching everybody. */
    private suspend fun seedLibrary() {
        val folderId = fixture.library.folder()
        fixture.seed(
            fixture.library.song(path = """H:\Music\aria1.mp3""", title = "Aria 1", artist = "Aria", album = "A1", folderId = folderId),
            fixture.library.song(path = """H:\Music\bill1.mp3""", title = "Bill 1", artist = "Bill Evans", album = "Waltz", folderId = folderId),
            fixture.library.song(path = """H:\Music\miles1.mp3""", title = "Miles 1", artist = "Miles Davis", album = "Kind of Blue", folderId = folderId),
        )
    }

    private fun poster(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        File(posterDir, name).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

    private suspend fun chooseFolder() {
        fixture.settings.setArtistPosterFolder(posterDir.absolutePath)
        awaitUntil(describe = { viewModel.uiState.value }) { it.folder != null && !it.isLoading }
    }

    private fun state() = viewModel.uiState.value

    // ------------------------------------------------------------------ tests

    @Test
    fun `the folder is listed with only its poster files, sorted by name`() = runBlocking<Unit> {
        seedLibrary()
        poster("Miles Davis.png")
        poster("Bill Evans.JPG")   // the extension check is case-insensitive, the name sort is not
        poster("aria.jpeg")
        poster("notes.txt")        // an extension the app never uses as a poster
        poster("no-extension")     // no extension at all, so `substringAfterLast` finds nothing
        poster("nested/Aria.mp4")  // a *directory* entry: Android's SafDocuments skipped those too

        chooseFolder()

        val folder = assertNotNull(state().folder, "a chosen folder is always reported back")
        assertEquals(posterDir.absolutePath, folder.path)
        assertEquals(
            listOf("aria.jpeg", "Bill Evans.JPG", "Miles Davis.png"),
            folder.posters?.map { it.name },
            "only the three posters, ordered by *lowercased* name -- which is why the lowercase `aria.jpeg` " +
                "sorts above `Bill Evans.JPG`",
        )
        // The path and the timestamp are what Coil keys its cache on, so both have to be the real ones.
        val bill = assertNotNull(folder.posters?.first { it.name == "Bill Evans.JPG" })
        assertEquals(File(posterDir, "Bill Evans.JPG").absolutePath, bill.path)
        assertEquals(File(posterDir, "Bill Evans.JPG").lastModified(), bill.lastModified)
        assertTrue(bill.lastModified > 0L, "a file written a moment ago carries a timestamp")
    }

    @Test
    fun `an empty folder is a folder with nothing in it, not an unavailable one`() = runBlocking<Unit> {
        seedLibrary()
        chooseFolder()

        val folder = assertNotNull(state().folder)
        assertEquals(emptyList(), folder.posters, "an empty list means 'listed, nothing matched'")
        assertEquals(0, folder.matchedPosterCount)
        assertFalse(state().error, "an empty folder is not an error")
    }

    @Test
    fun `a folder that is gone is reported unavailable instead of empty`() = runBlocking<Unit> {
        seedLibrary()
        poster("Aria.png")
        chooseFolder()
        assertEquals(1, state().folder?.posters?.size, "the folder reads before it is deleted")

        assertTrue(posterDir.deleteRecursively(), "the fixture folder has to be removable")

        // Only the reader can notice this: nothing in settings changed, so the state has to be asked again.
        viewModel.refresh()
        awaitUntil(describe = { state().folder }) { it?.posters == null }

        assertNull(
            state().folder?.posters,
            "`null` is the unavailable branch the screen renders with its own message; an empty list would " +
                "claim the folder is fine and just empty",
        )
    }

    @Test
    fun `matched posters are counted against the library's artists, case-folded`() = runBlocking<Unit> {
        seedLibrary()
        poster("aria.jpeg")             // "Aria" in the library -- matched by name, not by case
        poster("BILL EVANS.png")        // "Bill Evans"
        poster("Miles Davis_live.jpg")  // the suffixed variant of "Miles Davis"
        poster("Miles Davis Extra.jpg") // *not* the same artist: the name has to end where the artist ends
        poster("Nobody.jpg")            // no such artist in the library

        chooseFolder()

        assertEquals(
            3,
            state().folder?.matchedPosterCount,
            "three of the five files name a library artist; the counter is the app's own matcher",
        )
    }

    @Test
    fun `choosing a folder writes the setting, and removing it clears the setting but not the files`() =
        runBlocking<Unit> {
            seedLibrary()
            poster("Aria.png")
            chooseFolder()
            assertEquals(
                posterDir.absolutePath,
                fixture.settings.artistPosterFolder.first(),
                "the choice has to outlive the screen, so it is written to settings",
            )

            viewModel.removeFolder()
            awaitUntil(describe = { state().folder }) { it == null }

            assertEquals(0, state().folder?.posters?.size ?: 0)
            assertNull(state().folder, "the page falls back to the 'choose a folder' state")
            assertNull(
                fixture.settings.artistPosterFolder.first(),
                "removing the folder unregisters it",
            )
            assertTrue(
                File(posterDir, "Aria.png").isFile,
                "Android's tip on the confirm dialog says it too: the poster on disk stays where it is",
            )
            assertFalse(state().error, "removing a folder is not a failure")
        }

    @Test
    fun `refreshing bumps the revision so a rewritten poster is read again`() = runBlocking<Unit> {
        seedLibrary()
        val file = poster("Aria.png", bytes = byteArrayOf(1))
        chooseFolder()
        val before = state()
        assertEquals(1, before.folder?.posters?.size)
        assertEquals(before.revision, fixture.settings.artistPosterRevision.first())

        // An external tool rewrites the file in place: same name, new content and timestamp. Nothing in
        // the settings flow changes on its own, which is exactly why the page has a refresh action.
        Thread.sleep(10)
        file.writeBytes(byteArrayOf(9, 9))

        viewModel.refresh()
        awaitUntil(describe = { state().revision }) { it > before.revision }

        assertEquals(
            file.lastModified(),
            state().folder?.posters?.single()?.lastModified,
            "the re-read timestamp is what changes Coil's cache key, so the new bytes get decoded",
        )
    }

    @Test
    fun `a settings write failure is reported as an error instead of a silent no-op`() = runBlocking<Unit> {
        seedLibrary()
        // A real settings repository cannot be made to fail on demand, and this is the one branch that needs
        // a failure. Only the write the view model calls is overridden -- the reads stay real, so the page
        // still loads -- which is the shape `update {}` exists for: the page has to learn that the folder it
        // just picked did *not* get saved.
        val failing = object : SettingsRepository by fixture.settings {
            override suspend fun setArtistPosterFolder(path: String) {
                throw IOException("the preferences file is not writable")
            }
        }
        val failingViewModel = ArtistPosterFoldersViewModel(failing, fixture.index)
        collectors.keepCollecting(failingViewModel.uiState)
        awaitUntil(describe = { failingViewModel.uiState.value }) { !it.isLoading }

        failingViewModel.addFolder(posterDir.absolutePath)
        awaitUntil(describe = { failingViewModel.uiState.value }) { it.error }

        assertTrue(failingViewModel.uiState.value.error, "the failure reaches the state")
        assertNull(
            failingViewModel.uiState.value.folder,
            "and the folder is not claimed to be the chosen one when the write did not land",
        )
        assertFalse(failingViewModel.uiState.value.isLoading, "the failure does not leave the page spinning")
    }

    /** Same helper the sibling view-model tests carry: the state is only ever polled, but collecting it
     * keeps the flow hot for the lifetime of the test, as the screen does. */
    private fun CoroutineScope.keepCollecting(flow: StateFlow<*>) {
        launch { flow.collect { } }
    }
}
