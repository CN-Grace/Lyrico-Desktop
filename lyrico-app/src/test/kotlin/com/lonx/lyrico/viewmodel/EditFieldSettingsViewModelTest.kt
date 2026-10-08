package com.lonx.lyrico.viewmodel

import com.lonx.audiotag.model.CustomTagField
import com.lonx.lyrico.data.editfield.CustomTagKey
import com.lonx.lyrico.data.editfield.EditFieldConfigRepository
import com.lonx.lyrico.data.editfield.EditFieldDefinition
import com.lonx.lyrico.data.editfield.EditFieldKind
import com.lonx.lyrico.data.editfield.EditFieldRegistry
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.CustomTagKeyRepository
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.data.song.library.SongLibraryRepositoryImpl
import com.lonx.lyrico.data.song.tag.AudioTagMutationResolver
import com.lonx.lyrico.data.song.tag.AudioTagRepositoryImpl
import com.lonx.lyrico.data.song.tag.DefaultImageBytesFetcher
import com.lonx.lyrico.data.song.tag.ImageMimeTypeDetector
import com.lonx.lyrico.data.song.tag.PictureMutationResolver
import com.lonx.lyrico.data.song.tag.TagMapBuilder
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The edit-field settings screen, against a real config store, a real tag-key index and a real library.
 *
 * Two things here are easy to get subtly wrong and are therefore pinned:
 *
 * - "enabled" is three-state. `isEffectivelyEnabled` ANDs the per-field visibility with the *component*
 *   switch (only ReplayGain has one), so a test that only ever flips single fields would not notice the
 *   component override being dropped on the way to [EditFieldSettingsUiState.enabledCount].
 * - [EditFieldSettingsViewModel.showFieldSongs] builds a with/without split per row, and rows whose tags
 *   cannot be read are counted in neither. Anything that silently moves such a row into "without" would
 *   offer the user a fix for a file the app cannot even open, so the failure flag is asserted separately.
 */
class EditFieldSettingsViewModelTest {

    private lateinit var library: TestLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var config: EditFieldConfigRepository
    private lateinit var customTagKeys: CustomTagKeyRepository
    private lateinit var viewModel: EditFieldSettingsViewModel
    private lateinit var collectors: CoroutineScope
    private lateinit var fixtureFile: Path
    private var rootFolderId: Long = 0L

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        library = TestLibrary()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        collectors = CoroutineScope(Dispatchers.Default + SupervisorJob())
        config = EditFieldConfigRepository(
            createSettingsDataStore(
                Files.createTempFile("lyrico-edit-field-test", ".preferences_pb"),
                scope,
            )
        )
        customTagKeys = CustomTagKeyRepository(library.database.songCustomTagKeyDao())
        fixtureFile = Path.of(
            System.getProperty(FIXTURES_DIR_PROPERTY),
            "bladeenc.mp3",
        )
        assertTrue(Files.isRegularFile(fixtureFile), "missing audio fixture $fixtureFile")
        // One folder row: `folders.path` is unique, so every song shares a root the way a real library does.
        rootFolderId = runBlocking { library.folder() }
        newViewModel()
    }

    @AfterTest
    fun tearDown() {
        collectors.cancel()
        scope.cancel()
        library.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): EditFieldSettingsViewModel {
        val created = EditFieldSettingsViewModel(
            configRepository = config,
            customTagKeyRepository = customTagKeys,
            songLibraryRepository = SongLibraryRepositoryImpl(library.database),
            audioTagRepository = AudioTagRepositoryImpl(
                fileAccess = AudioFileAccess(),
                mutationResolver = AudioTagMutationResolver(
                    tagMapBuilder = TagMapBuilder(),
                    pictureResolver = PictureMutationResolver(
                        imageBytesFetcher = DefaultImageBytesFetcher(AudioFileAccess(), OkHttpClient()),
                        mimeTypeDetector = ImageMimeTypeDetector(),
                    ),
                ),
                appLogRepository = RecordingAppLogRepository(),
            ),
        )
        collectors.launch { created.uiState.collect { } }
        viewModel = created
        return created
    }

    private suspend fun awaitState(
        timeoutMillis: Long = 10_000L,
        predicate: (EditFieldSettingsUiState) -> Boolean,
    ) {
        awaitUntil(timeoutMillis = timeoutMillis, describe = { viewModel.uiState.value }) { predicate(it) }
    }

    /** Every mutation is awaited: the repository writes from a fresh coroutine per call. */

    private suspend fun awaitEnabled(code: String, enabled: Boolean) {
        awaitUntil(describe = { viewModel.uiState.value.items.firstOrNull { it.field.code == code } }) {
            it?.enabled == enabled
        }
    }

    private suspend fun seedSong(path: String, title: String? = null, album: String? = null): SongEntity {
        val song = library.song(path = path, title = title, album = album, folderId = rootFolderId)
        SongLibraryRepositoryImpl(library.database).upsertSongs(listOf(song))
        return song
    }

    @Test
    fun `the config is emitted as the default field list`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }

        val state = viewModel.uiState.value
        assertEquals(
            EditFieldRegistry.defaultOrder,
            state.items.map { it.field.code },
            "with nothing stored, the list is the registry order",
        )
        assertEquals(
            EditFieldRegistry.fields.count { it.defaultVisible },
            state.enabledCount,
            "the enabled count starts from the registry defaults",
        )
    }

    @Test
    fun `hiding a field is persisted and survives reopening the screen`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }

        viewModel.setEnabled("title", false)

        awaitEnabled("title", false)
        val enabledBefore = viewModel.uiState.value.enabledCount

        newViewModel()
        awaitEnabled("title", false)
        assertEquals(enabledBefore, viewModel.uiState.value.enabledCount)
    }

    @Test
    fun `disabling the ReplayGain component hides its whole block`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }
        val block = EditFieldRegistry.fields.filter { it.kind == EditFieldKind.ReplayGain }
        assertTrue(block.isNotEmpty(), "the test needs a composite block to disable")
        val enabledBefore = viewModel.uiState.value.enabledCount

        viewModel.setComponentEnabled("component:ReplayGain", false)

        awaitState { it.componentOverrides["component:ReplayGain"] == false }
        assertEquals(
            enabledBefore - block.count { it.defaultVisible },
            viewModel.uiState.value.enabledCount,
            "the component switch removes every member of the block from the count",
        )
        assertTrue(
            viewModel.uiState.value.items.filter { it.field.kind == EditFieldKind.ReplayGain }.all { it.enabled },
            "the per-field switches are untouched: the component is a separate, higher-level gate",
        )
    }

    @Test
    fun `a custom tag key is normalized before it is stored`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }

        val accepted = viewModel.addCustomTag("  scene  ")

        assertTrue(accepted)
        assertNull(viewModel.uiState.value.inputError)
        awaitUntil(describe = { viewModel.uiState.value.items.map { it.field.code } }) {
            EditFieldRegistry.customTagCode("SCENE") in it
        }
        assertEquals(
            listOf("SCENE"),
            viewModel.uiState.value.items.filter { it.field.custom }.map { it.field.code.removePrefix("tag:") },
        )
    }

    @Test
    fun `a blank key is rejected as EMPTY and an unusable one as INVALID`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }

        assertFalse(viewModel.addCustomTag("   "))
        awaitUntil(describe = { viewModel.uiState.value.inputError }) { it == CustomTagKeyError.EMPTY }

        assertFalse(viewModel.addCustomTag("a".repeat(CustomTagKey.MAX_KEY_LENGTH + 1)))
        awaitUntil(describe = { viewModel.uiState.value.inputError }) { it == CustomTagKeyError.INVALID }

        assertFalse(viewModel.addCustomTag("bad\nkey"))
        awaitUntil(describe = { viewModel.uiState.value.inputError }) { it == CustomTagKeyError.INVALID }
        assertTrue(viewModel.uiState.value.items.none { it.field.custom }, "nothing was added along the way")
    }

    @Test
    fun `a key that is already in the list is rejected as DUPLICATE however it is spelled`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }
        assertTrue(viewModel.addCustomTag("SCENE"))
        awaitUntil(describe = { viewModel.uiState.value.items.size }) { it > EditFieldRegistry.fields.size }

        val accepted = viewModel.addCustomTag("  scene ")

        assertFalse(accepted)
        awaitUntil(describe = { viewModel.uiState.value.inputError }) { it == CustomTagKeyError.DUPLICATE }
        assertEquals(
            1,
            viewModel.uiState.value.items.count { it.field.custom },
            "the duplicate did not add a second entry",
        )
    }

    @Test
    fun `clearing the input error leaves the config alone`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }
        viewModel.addCustomTag(" ")
        awaitUntil(describe = { viewModel.uiState.value.inputError }) { it != null }

        viewModel.clearInputError()

        awaitUntil(describe = { viewModel.uiState.value.inputError }) { it == null }
        assertEquals(EditFieldRegistry.defaultOrder, viewModel.uiState.value.items.map { it.field.code })
    }

    @Test
    fun `keys found in the library are offered, and stop being offered once added`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }
        customTagKeys.replaceForSong(SONG_A, listOf(CustomTagField("SCENE", "night")))
        customTagKeys.replaceForSong(SONG_B, listOf(CustomTagField("SCENE", "dawn"), CustomTagField("ZED", "z")))

        awaitUntil(describe = { viewModel.uiState.value.availableKeys }) { it.size == 2 }
        assertEquals(
            listOf("SCENE", "ZED"),
            viewModel.uiState.value.availableKeys,
            "most-used first, then alphabetically",
        )

        assertTrue(viewModel.addCustomTag("SCENE"))

        awaitUntil(describe = { viewModel.uiState.value.availableKeys }) { it.size == 1 }
        assertEquals(listOf("ZED"), viewModel.uiState.value.availableKeys)
    }

    @Test
    fun `addAvailableKey does not need the caller to normalize the key`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }
        customTagKeys.replaceForSong(SONG_A, listOf(CustomTagField("SCENE", "night")))
        awaitUntil(describe = { viewModel.uiState.value.availableKeys }) { it.isNotEmpty() }

        viewModel.addAvailableKey("  scene ")

        awaitUntil(describe = { viewModel.uiState.value.items.count { it.field.custom } }) { it == 1 }
    }

    @Test
    fun `removing a custom tag takes it out of the list and offers its key again`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }
        customTagKeys.replaceForSong(SONG_A, listOf(CustomTagField("SCENE", "night")))
        assertTrue(viewModel.addCustomTag("SCENE"))
        awaitUntil(describe = { viewModel.uiState.value.availableKeys }) { it.isEmpty() }

        viewModel.removeCustomTag("SCENE")

        awaitUntil(describe = { viewModel.uiState.value.items.count { it.field.custom } }) { it == 0 }
        awaitUntil(describe = { viewModel.uiState.value.availableKeys }) { it == listOf("SCENE") }
    }

    @Test
    fun `resetting restores the defaults but keeps the library keys on offer`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }
        customTagKeys.replaceForSong(SONG_A, listOf(CustomTagField("SCENE", "night")))
        assertTrue(viewModel.addCustomTag("SCENE"))
        viewModel.setEnabled("title", false)
        awaitEnabled("title", false)

        viewModel.resetAll()

        awaitState { it.items.map { item -> item.field.code } == EditFieldRegistry.defaultOrder }
        assertEquals(EditFieldRegistry.fields.count { it.defaultVisible }, viewModel.uiState.value.enabledCount)
        assertEquals(
            listOf("SCENE"),
            viewModel.uiState.value.availableKeys,
            "the library still has the key; only the list membership was reset",
        )
    }

    @Test
    fun `the ReplayGain block can be reordered without disturbing the other blocks`() = runBlocking<Unit> {
        awaitState { it.items.isNotEmpty() }
        val others = viewModel.uiState.value.items.map { it.field.code }
            .filterNot { code -> EditFieldRegistry.fieldMap[code]?.kind == EditFieldKind.ReplayGain }

        viewModel.setComponentOrder("component:ReplayGain", listOf("album_peak", "album_gain"))

        awaitUntil(describe = { viewModel.uiState.value.items.map { it.field.code } }) { codes ->
            codes.indexOf("album_peak") < codes.indexOf("album_gain")
        }
        val codes = viewModel.uiState.value.items.map { it.field.code }
        assertEquals(
            others,
            codes.filterNot { code -> EditFieldRegistry.fieldMap[code]?.kind == EditFieldKind.ReplayGain },
            "only the members of the named component moved",
        )
    }

    @Test
    fun `the song list is split into songs with and without the field`() = runBlocking<Unit> {
        seedSong("""H:\Music\a.mp3""", title = "With title")
        seedSong("""H:\Music\b.mp3""", title = null)
        seedSong("""H:\Music\c.mp3""", title = "Also with title")
        awaitState { it.items.isNotEmpty() }

        viewModel.showFieldSongs(field("title"))

        awaitState { !it.isLoadingSongs && it.songsWithField.size + it.songsWithoutField.size == 3 }
        val state = viewModel.uiState.value
        assertEquals("title", state.selectedField?.code)
        assertEquals(
            listOf("Also with title", "With title"),
            state.songsWithField.mapNotNull { it.title }.sorted(),
            "sorted by title, so the rows are stable",
        )
        assertEquals(1, state.songsWithoutField.size)
        assertNull(state.songsWithoutField.single().title)
        assertFalse(state.songsLoadFailed, "a blank tag is not a read failure")
        assertFalse(state.songsWithFieldTruncated)
        assertFalse(state.songsWithoutFieldTruncated)
    }

    @Test
    fun `a custom tag field uses the tag key index instead of a column`() = runBlocking<Unit> {
        val tagged = seedSong("""H:\Music\tagged.mp3""", title = "Tagged")
        seedSong("""H:\Music\plain.mp3""", title = "Plain")
        customTagKeys.replaceForSong(tagged.uri, listOf(CustomTagField("SCENE", "night")))
        awaitState { it.items.isNotEmpty() }
        assertTrue(viewModel.addCustomTag("SCENE"))
        awaitUntil(describe = { viewModel.uiState.value.items.count { it.field.custom } }) { it == 1 }
        val customField = viewModel.uiState.value.items.single { it.field.custom }.field

        viewModel.showFieldSongs(customField)

        awaitState { !it.isLoadingSongs && it.songsWithField.isNotEmpty() }
        assertEquals(listOf("Tagged"), viewModel.uiState.value.songsWithField.mapNotNull { it.title })
        assertEquals(listOf("Plain"), viewModel.uiState.value.songsWithoutField.mapNotNull { it.title })
    }

    @Test
    fun `a file that cannot be opened is reported as a file without a cover`() = runBlocking<Unit> {
        seedSong("""H:\Music\missing.mp3""", title = "Gone")
        awaitState { it.items.isNotEmpty() }

        // The cover check is the one branch that opens the file. A lenient tag read swallows the failure
        // and answers "no pictures", so a row for a file that is gone from disk is offered to the user as
        // a row without a cover. Pinned because it is a real consequence of the lenient read, not because
        // it is the desired answer: the alternative would be a distinct "unreadable" bucket per row.
        viewModel.showFieldSongs(field("picture"))

        awaitState { !it.isLoadingSongs && it.songsWithoutField.isNotEmpty() }
        assertEquals(listOf("Gone"), viewModel.uiState.value.songsWithoutField.mapNotNull { it.title })
        assertFalse(viewModel.uiState.value.songsLoadFailed)
    }

    @Test
    fun `the component branch reports rows it cannot inspect instead of guessing`() = runBlocking<Unit> {
        seedSong("""H:\Music.mp3""", title = "Titled")
        awaitState { it.items.isNotEmpty() }

        // `lyrics_offset` has no metadata target, and the component branch resolves a target per row via
        // requireNotNull -- so it fails on every row rather than answering from a column it does not have.
        // No screen asks for this today; the test exists so the failure flag cannot become dead code that
        // silently reports an empty library the day a caller does.
        viewModel.showFieldSongs(field("lyrics_offset"), component = true)

        awaitState { !it.isLoadingSongs }
        assertTrue(viewModel.uiState.value.songsLoadFailed)
        assertTrue(viewModel.uiState.value.songsWithField.isEmpty())
        assertTrue(viewModel.uiState.value.songsWithoutField.isEmpty())
    }

    @Test
    fun `a coverless file lands in the without list`() = runBlocking<Unit> {
        val coverless = Files.createTempDirectory("lyrico-edit-field-fixture")
        val copied = coverless.resolve("bladeenc.mp3")
        Files.copy(fixtureFile, copied, StandardCopyOption.REPLACE_EXISTING)
        try {
            seedSong(copied.toRealPath().toString(), title = "No cover")
            awaitState { it.items.isNotEmpty() }

            viewModel.showFieldSongs(field("picture"))

            awaitState { !it.isLoadingSongs && it.songsWithoutField.isNotEmpty() }
            assertEquals(listOf("No cover"), viewModel.uiState.value.songsWithoutField.mapNotNull { it.title })
            assertFalse(viewModel.uiState.value.songsLoadFailed)
        } finally {
            coverless.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the last request wins when a second field is opened before the first finishes`() = runBlocking<Unit> {
        seedSong("""H:\Music\a.mp3""", title = "Titled", album = "Album")
        seedSong("""H:\Music\b.mp3""", title = null, album = null)
        awaitState { it.items.isNotEmpty() }

        viewModel.showFieldSongs(field("title"))
        viewModel.showFieldSongs(field("album"))

        awaitState { !it.isLoadingSongs && it.selectedField?.code == "album" }
        val state = viewModel.uiState.value
        assertEquals(listOf("Titled"), state.songsWithField.mapNotNull { it.title })
        assertEquals(1, state.songsWithoutField.size)
    }

    @Test
    fun `clearing the song panel drops the selection and the rows`() = runBlocking<Unit> {
        seedSong("""H:\Music\a.mp3""", title = "Titled")
        awaitState { it.items.isNotEmpty() }
        viewModel.showFieldSongs(field("title"))
        awaitState { !it.isLoadingSongs && it.songsWithField.isNotEmpty() }

        viewModel.clearFieldSongs()

        awaitState { it.selectedField == null }
        assertEquals(emptyList<SongEntity>(), viewModel.uiState.value.songsWithField)
        assertEquals(emptyList<SongEntity>(), viewModel.uiState.value.songsWithoutField)
        assertFalse(viewModel.uiState.value.isLoadingSongs)
    }

    @Test
    fun `the same field can be loaded again after being cleared`() = runBlocking<Unit> {
        seedSong("""H:\Music\a.mp3""", title = "Titled")
        awaitState { it.items.isNotEmpty() }
        viewModel.showFieldSongs(field("title"))
        awaitState { !it.isLoadingSongs && it.songsWithField.isNotEmpty() }
        viewModel.clearFieldSongs()
        awaitState { it.selectedField == null }

        viewModel.showFieldSongs(field("title"))

        awaitState { it.songsWithField.size == 1 }
        assertFalse(viewModel.uiState.value.songsLoadFailed)
    }

    private fun field(code: String): EditFieldDefinition =
        assertNotNull(EditFieldRegistry.fieldMap[code], "unknown field $code")

    private companion object {
        const val FIXTURES_DIR_PROPERTY = "lyrico.audiotag.fixtures.dir"
        const val SONG_A = """H:\Music\a.mp3"""
        const val SONG_B = """H:\Music\b.mp3"""
    }
}
