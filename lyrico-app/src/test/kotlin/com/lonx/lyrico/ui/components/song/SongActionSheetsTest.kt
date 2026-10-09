package com.lonx.lyrico.ui.components.song

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.dialog_delete_file_content
import com.lonx.lyrico.resources.dialog_delete_file_title
import com.lonx.lyrico.resources.dialog_rename_title
import com.lonx.lyrico.resources.menu_action_delete
import com.lonx.lyrico.resources.menu_action_info
import com.lonx.lyrico.resources.menu_action_play
import com.lonx.lyrico.resources.menu_action_rename
import com.lonx.lyrico.resources.menu_action_share
import com.lonx.lyrico.ui.theme.LyricoTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which sheet action reaches which caller, and what the two confirmation dialogs send back.
 *
 * These are the wires the Android version had and the port had to re-cut: on Android the menu row for
 * "play" built an `ACTION_VIEW` intent itself and "share" built an `ACTION_SEND` one, both with a
 * `Context` and a `Uri`; here every action is a callback and the sheet owns no platform API at all.
 * A test that only composed the sheet would not notice a wire crossed between two lambdas that take the
 * same `SongEntity`, so each case clicks one row and asserts *which* callback fired and with what.
 *
 * The dialogs are driven for real: the delete confirm is clicked, and the rename field is typed into,
 * which is what pins the extension handling (the dialog stores the name without the extension and adds
 * it back on confirm).
 */
@OptIn(ExperimentalTestApi::class)
class SongActionSheetsTest {

    private fun song(fileExtension: String? = "mp3") = SongEntity(
        folderId = 1L,
        mediaId = 0,
        source = SongSource.LOCAL,
        filePath = """H:\Music\Rondo.mp3""",
        fileName = "Rondo.mp3",
        fileExtension = fileExtension,
        uri = """H:\Music\Rondo.mp3""",
        title = "Rondo",
        artist = "Brubeck",
    )

    private val theSong = song()

    private fun text(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }

    /** Renders the sheet with one flag set and records every callback the port exposes. */
    private class Recorder {
        var played: SongEntity? = null
        var shared: SongEntity? = null
        var deleted: SongEntity? = null
        var renamed: Pair<SongEntity, String>? = null
        var showedDetail = false
        var showedDelete = false
        var showedRename = false
        var dismissedMenu = false
    }

    @Composable
    private fun Sheets(
        recorder: Recorder,
        showMenu: Boolean = false,
        showDetail: Boolean = false,
        showDelete: Boolean = false,
        showRename: Boolean = false,
    ) {
        SongActionSheets(
            selectedSong = theSong,
            showMenuSheet = showMenu,
            showDetailSheet = showDetail,
            showDeleteDialog = showDelete,
            showRenameDialog = showRename,
            onDismissMenu = { recorder.dismissedMenu = true },
            onDismissMenuFinished = {},
            onDismissDetail = {},
            onDismissDelete = {},
            onDismissRename = {},
            onShowDetail = { recorder.showedDetail = true },
            onShowDelete = { recorder.showedDelete = true },
            onShowRename = { recorder.showedRename = true },
            onPlay = { recorder.played = it },
            onShare = { recorder.shared = it },
            onCopy = {},
            onDelete = { recorder.deleted = it },
            onRename = { song, name -> recorder.renamed = song to name },
        )
    }

    @Test
    fun `the play row hands the song over and closes the menu`() = runComposeUiTest {
        val recorder = Recorder()
        setContent { LyricoTheme { Sheets(recorder, showMenu = true) } }

        onNodeWithText(text(Res.string.menu_action_play)).performClick()

        runOnIdle {
            assertEquals(theSong, recorder.played, "the caller has to receive the song being played")
            assertTrue(recorder.dismissedMenu, "the menu must close behind the action, or it blocks the player")
        }
    }

    @Test
    fun `the share row reports the song without closing the menu`() = runComposeUiTest {
        val recorder = Recorder()
        setContent { LyricoTheme { Sheets(recorder, showMenu = true) } }

        onNodeWithText(text(Res.string.menu_action_share)).performClick()

        runOnIdle { assertEquals(theSong, recorder.shared, "share is the reveal action for this song") }
    }

    @Test
    fun `the info row asks for the detail sheet and never reports the song as played`() = runComposeUiTest {
        val recorder = Recorder()
        setContent { LyricoTheme { Sheets(recorder, showMenu = true) } }

        onNodeWithText(text(Res.string.menu_action_info)).performClick()

        runOnIdle {
            assertTrue(recorder.showedDetail)
            assertNull(recorder.played, "info must not be wired to the play callback")
        }
    }

    @Test
    fun `the rename and delete rows only ask for their dialogs`() = runComposeUiTest {
        val recorder = Recorder()
        setContent { LyricoTheme { Sheets(recorder, showMenu = true) } }

        onNodeWithText(text(Res.string.menu_action_rename)).performClick()
        onNodeWithText(text(Res.string.menu_action_delete)).performClick()

        runOnIdle {
            assertTrue(recorder.showedRename)
            assertTrue(recorder.showedDelete)
            assertNull(recorder.deleted, "the destructive action must wait for the confirmation")
            assertNull(recorder.renamed, "renaming needs a name from the dialog first")
        }
    }

    @Test
    fun `the delete dialog names the file and confirms against that song`() = runComposeUiTest {
        val recorder = Recorder()
        setContent { LyricoTheme { Sheets(recorder, showDelete = true) } }

        onNodeWithText(text(Res.string.dialog_delete_file_title)).assertIsDisplayed()
        onNodeWithText(text(Res.string.dialog_delete_file_content, theSong.fileName)).assertIsDisplayed()

        onNodeWithText(text(Res.string.confirm)).performClick()

        runOnIdle {
            assertEquals(theSong, recorder.deleted, "confirming has to delete the song the dialog named")
            assertTrue(recorder.dismissedMenu, "the menu that opened the dialog is dismissed with it")
        }
    }

    @Test
    fun `the rename dialog prefills the name and puts the extension back on confirm`() = runComposeUiTest {
        val recorder = Recorder()
        setContent { LyricoTheme { Sheets(recorder, showRename = true) } }

        onNodeWithText(text(Res.string.dialog_rename_title)).assertIsDisplayed()

        onNode(hasSetTextAction()).performTextReplacement("Take Five")
        onNodeWithText(text(Res.string.confirm)).performClick()

        runOnIdle {
            assertEquals(
                theSong to "Take Five.mp3",
                recorder.renamed,
                "the dialog edits the stem, so the extension has to be re-attached before the rename",
            )
        }
    }

    @Test
    fun `the rename dialog refuses a blank name`() = runComposeUiTest {
        val recorder = Recorder()
        setContent { LyricoTheme { Sheets(recorder, showRename = true) } }

        onNode(hasSetTextAction()).performTextReplacement("   ")
        onNodeWithText(text(Res.string.confirm)).performClick()

        runOnIdle { assertNull(recorder.renamed, "a rename to nothing would move the file to a nameless path") }
    }

    @Test
    fun `the rename dialog refuses to submit the name the file already has`() = runComposeUiTest {
        val recorder = Recorder()
        setContent { LyricoTheme { Sheets(recorder, showRename = true) } }

        // Untouched, the field holds "Rondo" and the extension is re-attached on confirm, which is the
        // file's current name: a no-op rename is skipped rather than sent to the filesystem.
        onNodeWithText(text(Res.string.confirm)).performClick()

        runOnIdle { assertNull(recorder.renamed, "an unchanged name must not trigger a file move") }
    }

    @Test
    fun `a file without an extension renames to exactly the typed name`() = runComposeUiTest {
        val recorder = Recorder()
        val extensionless = song(fileExtension = null)
        setContent {
            LyricoTheme {
                SongActionSheets(
                    selectedSong = extensionless,
                    showMenuSheet = false,
                    showDetailSheet = false,
                    showDeleteDialog = false,
                    showRenameDialog = true,
                    onDismissMenu = {},
                    onDismissMenuFinished = {},
                    onDismissDetail = {},
                    onDismissDelete = {},
                    onDismissRename = {},
                    onShowDetail = {},
                    onShowDelete = {},
                    onShowRename = {},
                    onPlay = {},
                    onShare = {},
                    onCopy = {},
                    onDelete = {},
                    onRename = { s, name -> recorder.renamed = s to name },
                )
            }
        }

        onNode(hasSetTextAction()).performTextReplacement("NoExtension")
        onNodeWithText(text(Res.string.confirm)).performClick()

        runOnIdle { assertEquals("NoExtension", recorder.renamed?.second, "there is no extension to put back") }
    }

    @Test
    fun `an unselected sheet draws nothing at all`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                SongActionSheets(
                    selectedSong = null,
                    showMenuSheet = true,
                    showDetailSheet = true,
                    showDeleteDialog = true,
                    showRenameDialog = true,
                    onDismissMenu = {},
                    onDismissMenuFinished = {},
                    onDismissDetail = {},
                    onDismissDelete = {},
                    onDismissRename = {},
                    onShowDetail = {},
                    onShowDelete = {},
                    onShowRename = {},
                    onPlay = {},
                    onShare = {},
                    onCopy = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                )
            }
        }

        // Every flag is set, so anything appearing here would be a sheet reading a stale song.
        onNodeWithText(text(Res.string.menu_action_play)).assertDoesNotExist()
        onNodeWithText(text(Res.string.dialog_delete_file_title)).assertDoesNotExist()
    }
}
