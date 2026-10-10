package com.lonx.lyrico.ui.components.song

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.label_album
import com.lonx.lyrico.resources.label_bitrate
import com.lonx.lyrico.resources.label_channels
import com.lonx.lyrico.resources.label_duration
import com.lonx.lyrico.resources.label_file_path
import com.lonx.lyrico.resources.label_file_size
import com.lonx.lyrico.resources.label_genre
import com.lonx.lyrico.resources.unknown_artist
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.utils.FileSizeFormatter
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The song's metadata sheet, and the one thing about it that changed on the way over.
 *
 * On Android the sheet copied to the clipboard itself and confirmed with a `Toast`; on desktop
 * `android.widget.Toast` does not exist, so the copy is hoisted to the caller
 * (`onCopy: (String) -> Unit`) and the sheet only reports the value. That is what these tests pin:
 * **the callback receives the raw value**, not the rendered `label: value` pair, which is what makes
 * the copied text pasteable.
 *
 * The rest is a checklist of the rows that show up. `SongDetailItem` is public, so the copy contract is
 * asserted directly on it with a single row; the sheet-level test then proves the same path is what the
 * rows are wired to. The file-size row is asserted against `FileSizeFormatter` itself rather than a
 * literal, because the formatter follows the machine's locale and the shipped app does the same.
 */
@OptIn(ExperimentalTestApi::class)
class SongDetailBottomSheetTest {

    private fun song() = SongEntity(
        folderId = 1L,
        mediaId = 0,
        source = SongSource.LOCAL,
        filePath = """H:\Music\Rondo.mp3""",
        fileName = "Rondo.mp3",
        uri = """H:\Music\Rondo.mp3""",
        title = "Blue Rondo à la Turk",
        artist = "Dave Brubeck",
        album = "Time Out",
        genre = "Jazz",
        durationMilliseconds = 210_400,
        bitrate = 320,
        sampleRate = 44_100,
        channels = 2,
        fileSize = 1_500_000L,
    )

    /** Formatted with [String.format]; see `StringFormattingGuardTest` for why not `getString(res, *args)`. */
    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    @Test
    fun `the rows show the song's metadata`() = runComposeUiTest {
        val song = song()
        setContent {
            LyricoTheme {
                SongDetailBottomSheet(show = true, song = song, onDismissRequest = {}, onCopy = {})
            }
        }

        onNodeWithText("Blue Rondo à la Turk").assertIsDisplayed()
        onNodeWithText("Dave Brubeck").assertIsDisplayed()

        onNodeWithText(text(Res.string.label_album)).assertIsDisplayed()
        onNodeWithText("Time Out").assertIsDisplayed()
        onNodeWithText(text(Res.string.label_genre)).assertIsDisplayed()
        onNodeWithText("Jazz").assertIsDisplayed()

        onNodeWithText(text(Res.string.label_duration)).assertIsDisplayed()
        onNodeWithText("3:30").assertIsDisplayed()
        onNodeWithText(text(Res.string.label_bitrate)).assertIsDisplayed()
        onNodeWithText("320 kbps").assertIsDisplayed()
        onNodeWithText(text(Res.string.label_channels)).assertIsDisplayed()

        onNodeWithText(text(Res.string.label_file_path)).assertIsDisplayed()
        onNodeWithText("""H:\Music\Rondo.mp3""").assertIsDisplayed()

        // Formatted, not echoed: the sheet renders the ported AOSP formatter, so this is the assertion
        // that the row goes through it. Computed here so a non-US locale cannot flip the result.
        onNodeWithText(text(Res.string.label_file_size)).assertIsDisplayed()
        onNodeWithText(FileSizeFormatter.format(1_500_000L)).assertIsDisplayed()
    }

    @Test
    fun `a song without an artist falls back to the unknown-artist label`() = runComposeUiTest {
        // Resolved before composing: `text` blocks, and the label has to come from the same resource
        // path the sheet uses rather than from a translation hard-coded here.
        val unknownArtist = text(Res.string.unknown_artist)

        setContent {
            LyricoTheme {
                SongDetailBottomSheet(
                    show = true,
                    song = song().copy(artist = null),
                    onDismissRequest = {},
                    onCopy = {},
                )
            }
        }

        onNodeWithText(unknownArtist).assertIsDisplayed()
    }

    @Test
    fun `a field with no value gets no row at all`() = runComposeUiTest {
        val song = song().copy(album = null, genre = "   ")
        setContent {
            LyricoTheme {
                SongDetailBottomSheet(show = true, song = song, onDismissRequest = {}, onCopy = {})
            }
        }

        // Album is absent and genre is whitespace: both rows are skipped rather than shown empty, which
        // is what `SongDetailItem` returning early does.
        onNodeWithText(text(Res.string.label_album)).assertDoesNotExist()
        onNodeWithText(text(Res.string.label_genre)).assertDoesNotExist()
        // The control: a field that does have a value is still there, so the two assertions above are
        // about the missing values and not about a sheet that failed to render.
        onNodeWithText(text(Res.string.label_file_path)).assertIsDisplayed()
    }

    @Test
    fun `the copy action reports the raw value`() = runComposeUiTest {
        var copied: String? = null
        setContent {
            LyricoTheme {
                SongDetailItem(label = "File path", value = """H:\Music\Rondo.mp3""") { copied = it }
            }
        }

        onNodeWithContentDescription("复制").performClick()

        runOnIdle {
            assertEquals(
                """H:\Music\Rondo.mp3""",
                copied,
                "the clipboard has to receive the value alone; a \"label: value\" string would not paste anywhere",
            )
        }
    }

    @Test
    fun `the sheet's copy icon is wired to the row it belongs to`() = runComposeUiTest {
        var copied: String? = null
        // Only the path has a value, so this sheet has exactly one copyable row: clicking the icon can
        // only be attributed to that one.
        val song = song().copy(
            album = null,
            genre = null,
            durationMilliseconds = 0,
            bitrate = 0,
            sampleRate = 0,
            channels = 0,
            fileSize = 0,
        )
        setContent {
            LyricoTheme {
                SongDetailBottomSheet(show = true, song = song, onDismissRequest = {}, onCopy = { copied = it })
            }
        }

        onNodeWithContentDescription("复制").performClick()

        runOnIdle { assertEquals(song.filePath, copied) }
    }

    @Test
    fun `a row without a copy handler renders no copy affordance`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                SongDetailItem(label = "File path", value = """H:\Music\Rondo.mp3""")
            }
        }

        onNodeWithContentDescription("复制").assertDoesNotExist()
    }
}
