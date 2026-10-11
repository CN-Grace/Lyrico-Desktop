package com.lonx.lyrico.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lonx.lyrico.ExternalAudioEditHost
import com.lonx.lyrico.LocalExternalAudioEditHost
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.search.LyricsSearchResult
import com.lonx.lyrico.data.repository.PlaybackRepository
import com.lonx.lyrico.data.repository.PlaybackResult
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.edit_field_settings_title
import com.lonx.lyrico.resources.menu_play_music
import com.lonx.lyrico.resources.msg_save_success
import com.lonx.lyrico.resources.no_player_found
import com.lonx.lyrico.screens.library.placeTaggedFixture
import com.lonx.lyrico.support.HeadlessMainDispatcher
import com.lonx.lyrico.support.PlatformLogCapture
import com.lonx.lyrico.ui.navigation.EditMetadataDestination
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.navigation.SearchCoverDestination
import com.lonx.lyrico.ui.navigation.SearchLyricsDestination
import com.lonx.lyrico.ui.navigation.SearchResultsDestination
import com.lonx.lyrico.ui.navigation.rememberNavigator
import com.lonx.lyrico.ui.navigation.rememberResultRecipient
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.utils.LibraryScanManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The metadata editor, driven against a real audio file and the two seams the port replaced.
 *
 * This is the first ported screen whose Android version talked to the *outside* of the app: it picked
 * files through SAF, asked for write permission through an `IntentSender`, handed songs to an `Intent`
 * chooser, and reported an external edit's outcome back to the `Activity` that hosted it. Every one of
 * those mechanisms is gone rather than shimmed (the screen's KDoc and `PLAN.md` name each gap), and the
 * tests here are the ones a headless frame can still drive:
 *
 * * **The editor opens on the file it was pointed at.** With the real [LyricoNavHost], a scan of a real
 *   audio file produces a row, and tapping that row lands in the editor showing the tags that were
 *   really written to that file. That is what proves the `edit_metadata` registration and its encoded
 *   path argument, which is the half of this batch no unit test can reach.
 * * **Play goes to the shell, and a missing player reaches the user.** `PlaybackRepository` is the one
 *   OS seam replaced (a real `Desktop.open` would launch the machine's media player), so the outcome of
 *   `no_player_found` is asserted against a fake shell.
 * * **A destination that is declared but not registered is logged, not fatal.** `EditFieldSettings`'
 *   screen belongs to a later batch, so the editor's own action for it has to report the miss through
 *   the navigator instead of taking the window down.
 * * **An external edit reports back.** `ExternalAudioEditHost` is the composition-local replacement for
 *   the hosting `Activity`, and the request id is what lets a caller with two edits in flight tell them
 *   apart; a save has to hand `(requestId, saved)` to the host, and with no host present the miss has to
 *   be logged rather than swallowed.
 *
 * What cannot be driven here is equally deliberate. The file dialogs are native AWT windows behind
 * `FileOpenPicker` / `FileSavePicker` (covered by their own tests), and the crop dialog is only reachable
 * through a decoded image, so the pickers are exercised where they are implemented instead of through a
 * click that no headless frame can make.
 */
@OptIn(ExperimentalTestApi::class)
class EditMetadataScreenTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-edit-metadata").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-edit-metadata-library")

    private lateinit var database: LyricoDatabase
    private lateinit var playback: FakePlaybackRepository

    private val viewModelStore = ViewModelStore()

    /**
     * The editor's back action and the library's row both navigate, and a navigation writes the new
     * entry's lifecycle. Under a headless frame that write has to be allowed on the thread the click
     * runs on; [HeadlessMainDispatcher] explains why and what it costs. This test is the first in the
     * project to navigate, so it is the first to need it.
     */
    private val mainDispatcher = HeadlessMainDispatcher()

    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = this@EditMetadataScreenTest.viewModelStore
    }

    /**
     * The shell, replaced so that pressing play cannot launch the machine's media player.
     *
     * `FileUnavailable` rather than `Unsupported`: it is the outcome a Windows session with nothing
     * associated with the file type produces, and the one that has to reach the user as a snackbar.
     */
    private class FakePlaybackRepository : PlaybackRepository {
        val opened = mutableListOf<Path>()

        override fun open(path: Path): PlaybackResult {
            // `opened += path` would be ambiguous: `java.nio.file.Path` is `Iterable<Path>` (its name
            // elements), so the element overload and the elements overload are both candidates and the
            // compiler falls back to `opened = opened + path` on a `val`. `add` says which one is meant.
            opened.add(path)
            return PlaybackResult.FileUnavailable(path)
        }
    }

    /** The window that launched an external edit, recording what it was told. */
    private class RecordingExternalAudioEditHost : ExternalAudioEditHost {
        val results = mutableListOf<Pair<Long, Boolean>>()

        override fun finishExternalAudioEdit(requestId: Long, saved: Boolean) {
            results += requestId to saved
        }
    }

    @BeforeTest
    fun setUp() {
        mainDispatcher.install()
        runCatching { stopKoin() }
        playback = FakePlaybackRepository()
        startKoin {
            modules(
                desktopAppModule(directories),
                // Last definition wins in Koin, so this is the shell the editor hands songs to.
                module { single<PlaybackRepository> { playback } },
            )
        }
        database = koin()
        // Every audio fixture TagLib ships is shorter than a minute, and the app's own default is to
        // ignore short audio, so a scan would (correctly) drop them. This is the setting a user with
        // short files turns off, saved through the real settings store.
        runBlocking { settings().saveIgnoreShortAudio(false) }
    }

    /**
     * Clears the view models the mounted screens created, then stops the graph.
     *
     * The order matters and it is the same lesson `LibraryHomeScreenTest` recorded: a page's view model
     * lives in its `NavBackStackEntry`'s store, which hangs off the host store provided here. Without the
     * clear, a `viewModelScope` outlives the test still collecting a Room flow, and closing the database
     * underneath it fails on the AWT queue as `UncaughtExceptionsBeforeTest` -- against the *next* test.
     */
    @AfterTest
    fun tearDown() {
        assertTrue(
            viewModelStore.keys().isNotEmpty(),
            "the mounted editor has to put its view model in this test's store, but the store is empty",
        )
        viewModelStore.clear()
        mainDispatcher.restore()
        runCatching { stopKoin() }
        // Best effort: Room's connection pool may still hold the database file open.
        database.close()
        workingDir.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    private inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    private fun settings(): SettingsRepository = koin()

    /** A real audio file, in the library, with tags this test chose. */
    private fun taggedSong(title: String = "Editor Song"): Path =
        placeTaggedFixture(
            musicDir = musicDir,
            fixture = "bladeenc.mp3",
            relativeDir = "Album One",
            title = title,
            artist = ARTIST,
            album = "Editor Album",
            tags = koin<AudioTagRepository>(),
        )

    /**
     * The expected text for a string resource, formatted the way the screen formats it.
     *
     * [String.format] rather than the library's vararg `getString`: that overload ignores plain `%d`/`%s`
     * placeholders, so building the expectation with it would make the assertions agree with a screen
     * that renders the placeholder instead of the value (see `StringFormattingGuardTest`).
     */
    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    /**
     * The real host, mounted inside a store this class owns and clears.
     *
     * `LyricoNavHost()` is what the shipped window mounts, so the boot route and the `edit_metadata`
     * registration are asserted rather than assumed; only the store owner differs from production, and
     * it differs by being something the test can clear (see [tearDown]).
     */
    @Composable
    private fun LibraryHost() {
        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
            LyricoTheme {
                LyricoNavHost()
            }
        }
    }

    /**
     * The editor inside a real `NavHost` entry, so `koinViewModel()` resolves the way the shipped host
     * builds it, with the window seams an external caller would set.
     */
    @Composable
    private fun EditorInTestHost(
        songFileUri: String,
        externalEditRequestId: Long? = null,
        host: ExternalAudioEditHost? = null,
    ) {
        val controller = rememberNavController()
        val navigator = rememberNavigator(controller)
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides viewModelStoreOwner,
            LocalExternalAudioEditHost provides host,
        ) {
            LyricoTheme {
                NavHost(
                    navController = controller,
                    // The concrete route, so the fake host starts on the editor the way an external
                    // caller's deep link would. The three result recipients are the real ones, keyed by
                    // each sender's base route: nothing sends a result here, and the keys are what
                    // `ResultRecipientTest` pins.
                    startDestination = EditMetadataDestination(songFileUri).route,
                ) {
                    composable(
                        route = EditMetadataDestination.PATTERN,
                        arguments = listOf(
                            navArgument(EditMetadataDestination.ARG_SONG_FILE_URI) {
                                type = NavType.StringType
                            }
                        ),
                    ) { entry ->
                        EditMetadataScreen(
                            navigator = navigator,
                            songFileUri = songFileUri,
                            onCoverSearchResult = rememberResultRecipient<String>(
                                handle = entry.savedStateHandle,
                                route = SearchCoverDestination.BASE,
                            ),
                            onLyricsResult = rememberResultRecipient<LyricsSearchResult>(
                                handle = entry.savedStateHandle,
                                route = SearchResultsDestination.BASE,
                            ),
                            onLyricsSearchResult = rememberResultRecipient<LyricsSearchResult>(
                                handle = entry.savedStateHandle,
                                route = SearchLyricsDestination.BASE,
                            ),
                            externalEditRequestId = externalEditRequestId,
                        )
                    }
                }
            }
        }
    }

    /**
     * Waits until the editor has read the file's tags and rendered them.
     *
     * `readMetadata` resolves the song, reads the file with TagLib and only then fills `editingTagData`
     * on a background dispatcher, so the form is empty until that lands. The gate is the artist value *in
     * an editable field*: the value alone is not enough, because the songs page renders the same artist in
     * its own row text, and a gate the page under test came from can be satisfied before the navigation
     * ever lands.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.awaitTagsRead() {
        waitUntil("the editor reads the tags and renders them", 20_000) {
            onAllNodes(hasSetTextAction() and hasText(ARTIST)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The bounds of the one clickable in a corner of the top bar.
     *
     * Both top-bar buttons are icon-only with `contentDescription = null` -- Android had it that way and
     * the port kept it -- so there is no text to address them by. What is stable across the headless
     * frame (1024x768 at density 1.0, so `dp` and `px` are the same number) is the corner each sits in:
     * the back icon is the only *button-sized* clickable in the top-left of the window, and the save icon
     * the only one in the top-right. The size bound is what makes the matcher precise rather than lucky:
     * every ancestor of those buttons spans the window, so a corner test alone matches the root, the top
     * bar and the scrolling list as well.
     */
    private fun inTopLeftCorner(): SemanticsMatcher =
        SemanticsMatcher("a clickable in the window's top-left corner") { node ->
            val bounds = node.boundsInRoot
            node.config.contains(SemanticsActions.OnClick) &&
                bounds.width > 0f && bounds.height > 0f &&
                bounds.top < 32f && bounds.left < 32f &&
                bounds.width < 64f && bounds.height < 64f
        }

    private fun inTopRightCorner(rootWidth: Float): SemanticsMatcher =
        SemanticsMatcher("a clickable in the window's top-right corner") { node ->
            val bounds = node.boundsInRoot
            node.config.contains(SemanticsActions.OnClick) &&
                bounds.width > 0f && bounds.height > 0f &&
                bounds.top < 32f && bounds.right > rootWidth - 32f &&
                bounds.width < 64f && bounds.height < 64f
        }

    /**
     * The main floating action button, not the search one that floats above it.
     *
     * Both carry the hardcoded English label `"Actions"` (`ExpandableFabMenu`'s own wart, kept from
     * Android), and both are the same size in the same corner. The search menu is composed first -- it
     * floats higher, over the main one -- so the last match is the main button. Getting it wrong fails
     * loudly on the next line instead of clicking something unrelated and passing: the search menu has no
     * play item.
     */
    private fun SemanticsNodeInteractionsProvider.mainFab() =
        onAllNodesWithContentDescription("Actions").onLast()

    // ---------------------------------------------------------------- the shipped host and its route

    @Test
    fun `tapping a song in the library opens the editor on that file's tags`() = runComposeUiTest {
        taggedSong(title = "Row Song")

        setContent { LibraryHost() }

        // The scan runs for real, through the same manager the folder picker ends up calling; the picker
        // itself is a native window and is covered by the platform code's own seams.
        koin<LibraryScanManager>().addFolderAndScan(musicDir.toRealPath().toString())
        waitUntil("the scanned row is composed", 20_000) {
            onAllNodesWithText("Row Song").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText("Row Song").performClick()

        // The editor reads the tags off the file the row's URI points at.
        awaitTagsRead()

        // And back out the way a user would: with no external edit request to answer, the editor's back
        // action is a plain pop, so the library comes back and the form -- which lives in the entry that
        // was popped -- leaves with it. The two halves of the condition are only meaningful together: the
        // songs page has no editable fields, and the page being left is the only other place the row title
        // is rendered.
        onNode(inTopLeftCorner()).performClick()
        waitUntil("the library comes back without the editor's form", 5_000) {
            onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() &&
                onAllNodesWithText("Row Song").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---------------------------------------------------------------- playback

    @Test
    fun `play hands the song to the shell, and a missing player reaches the user`() = runComposeUiTest {
        val song = taggedSong()

        setContent { EditorInTestHost(songFileUri = song.toString()) }
        awaitTagsRead()

        mainFab().performClick()
        waitUntil("the FAB menu expands", 5_000) {
            onAllNodesWithText(text(Res.string.menu_play_music)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(text(Res.string.menu_play_music)).performClick()

        waitUntil("the missing player reaches the user", 5_000) {
            onAllNodesWithText(text(Res.string.no_player_found)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            listOf(Path.of(song.toString())),
            playback.opened,
            "the editor has to hand the shell the song it was opened for",
        )
    }

    // ---------------------------------------------------------------- the destination with no screen

    @Test
    fun `the field-settings action logs the destination that has no screen yet`() = runComposeUiTest {
        val song = taggedSong()
        val log = PlatformLogCapture().install()

        try {
            setContent { EditorInTestHost(songFileUri = song.toString()) }
            awaitTagsRead()

            mainFab().performClick()
            waitUntil("the FAB menu expands", 5_000) {
                onAllNodesWithText(text(Res.string.edit_field_settings_title))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText(text(Res.string.edit_field_settings_title)).performClick()

            waitUntil("the missing destination is reported", 5_000) {
                log.warnings.any { it.contains(FIELD_SETTINGS_ROUTE) }
            }
            val warning = log.warnings.single { it.contains(FIELD_SETTINGS_ROUTE) }
            assertTrue(
                warning.contains("not ported yet"),
                "the log line has to say why the navigation was dropped, got: $warning",
            )
            // The degradation is logged *and* survivable: the editor is still the screen on show, with its
            // FAB and the tags it read still rendered.
            assertTrue(
                onAllNodesWithText(ARTIST).fetchSemanticsNodes().isNotEmpty(),
                "the editor has to still be the screen on show after a destination it cannot reach",
            )
            mainFab().assertExists()
        } finally {
            log.restore()
        }
    }

    // ---------------------------------------------------------------- the external edit host

    @Test
    fun `saving an external edit request tells the window that launched it`() = runComposeUiTest {
        val song = taggedSong()
        val host = RecordingExternalAudioEditHost()

        setContent {
            EditorInTestHost(
                songFileUri = song.toString(),
                externalEditRequestId = EXTERNAL_REQUEST_ID,
                host = host,
            )
        }
        awaitTagsRead()

        onNode(inTopRightCorner(onRoot().fetchSemanticsNode().boundsInRoot.width)).performClick()

        waitUntil("the save confirmation", 20_000) {
            onAllNodesWithText(text(Res.string.msg_save_success)).fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil("the host is told the edit finished", 5_000) { host.results.isNotEmpty() }
        assertEquals(
            listOf(EXTERNAL_REQUEST_ID to true),
            host.results,
            "an external edit has to report which request finished and whether it was saved; without " +
                "the request id a caller with two edits in flight cannot tell them apart",
        )
    }

    @Test
    fun `an external edit with no host logs the miss instead of dropping the result`() = runComposeUiTest {
        val song = taggedSong()
        val log = PlatformLogCapture().install()

        try {
            setContent {
                EditorInTestHost(
                    songFileUri = song.toString(),
                    externalEditRequestId = EXTERNAL_REQUEST_ID,
                )
            }
            awaitTagsRead()

            onNode(inTopRightCorner(onRoot().fetchSemanticsNode().boundsInRoot.width)).performClick()

            // The save itself still succeeds -- the file is written and the user is told -- and only the
            // report back to a window that is not there is lost, which is the part that has to be visible
            // in the log rather than silently dropped.
            waitUntil("the save confirmation", 20_000) {
                onAllNodesWithText(text(Res.string.msg_save_success)).fetchSemanticsNodes().isNotEmpty()
            }
            waitUntil("the missing host is reported", 5_000) {
                log.warnings.any { it.contains(NO_HOST_WARNING) }
            }
            val warning = log.warnings.single { it.contains(NO_HOST_WARNING) }
            assertTrue(
                warning.contains(EXTERNAL_REQUEST_ID.toString()),
                "the log line has to name the request whose result was dropped, got: $warning",
            )
        } finally {
            log.restore()
        }
    }

    private companion object {
        const val ARTIST = "Editor Artist"

        /**
         * The route `EditFieldSettingsDestination` declares, spelled out rather than read back off the
         * declaration: the claim is about what the *navigator logs*, and reading the constant would make
         * the test agree with whatever value the declaration happens to hold.
         */
        const val FIELD_SETTINGS_ROUTE = "edit_field_settings"

        /** A fragment of the editor's own warning when no window host is present. */
        const val NO_HOST_WARNING = "没有宿主"

        const val EXTERNAL_REQUEST_ID = 11L
    }
}
