package com.lonx.lyrico.ui.components.song

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.ic_album_24dp
import com.lonx.lyrico.resources.unknown_artist
import com.lonx.lyrico.ui.theme.LyricoTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * One row of the song list, rendered headlessly.
 *
 * Three things are pinned here that the Android original could not state as assertions:
 *
 * 1. **The row shows what the user sorts by.** The Android version had a `@SuppressLint("DefaultLocale")`
 *    on `String.format("%d:%02d", ...)`, i.e. a lint warning saying the duration could be formatted with
 *    non-ASCII digits in some locales. Removing the annotation without checking would have been a silent
 *    behaviour claim; the duration assertion below is what replaces it.
 * 2. **The cover placeholder is a real, decodable image resource.** Android's `R.drawable.ic_album_24dp`
 *    is an XML vector, which does not compile on desktop at all, so it was converted to SVG by
 *    `scripts/migrate-vector-drawables.py`. `painterResource` succeeding is not the claim -- the
 *    assertion is the decoded intrinsic size, which is what tells SVG decoding apart from an empty
 *    painter that happens to have the right type.
 * 3. **Long press is what starts selection, and a plain click still opens the song.** That branch is
 *    pure logic inside `combinedClickable`, so it is provable without a device.
 *
 * Desktop drops the two `performHapticFeedback` calls the Android row made (`LocalView` does not exist
 * on desktop, and there is no vibration motor); no test can observe that, which is exactly why the
 * removal is documented in [SongListItem]'s KDoc instead.
 */
@OptIn(ExperimentalTestApi::class)
class SongListItemTest {

    private fun song(
        title: String? = "Blue Rondo à la Turk",
        artist: String? = "Dave Brubeck",
        album: String? = "Time Out",
        fileName: String = "blue_rondo.mp3",
        durationMilliseconds: Int = 210_400,
        bitrate: Int = 320,
    ) = SongEntity(
        folderId = 1L,
        mediaId = 0L,
        source = SongSource.LOCAL,
        filePath = """H:\Music\$fileName""",
        fileName = fileName,
        uri = """H:\Music\$fileName""",
        title = title,
        artist = artist,
        album = album,
        durationMilliseconds = durationMilliseconds,
        bitrate = bitrate,
    )

    @Test
    fun `the row shows the title, the artist, the format, the length and the bitrate`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                SongListItem(song = song(), onClick = {})
            }
        }

        onNodeWithText("Blue Rondo à la Turk").assertIsDisplayed()
        onNodeWithText("Dave Brubeck").assertIsDisplayed()
        onNodeWithText(" · Time Out").assertIsDisplayed()
        onNodeWithText("MP3").assertIsDisplayed()
        onNodeWithText("3:30").assertIsDisplayed()
        onNodeWithText("320kbps").assertIsDisplayed()
    }

    @Test
    fun `the cover slot is requested for this song`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                SongListItem(song = song(), onClick = {})
            }
        }

        // The AsyncImage carries the title as its content description, so this fails if the cover
        // request is ever dropped from the row (a silent loss of every cover in the library).
        onNodeWithContentDescription("Blue Rondo à la Turk").assertIsDisplayed()
    }

    @Test
    fun `the converted cover-placeholder icon decodes at its declared size`() = runComposeUiTest {
        var intrinsicSize: Size? = null
        var unknownArtist: String? = null

        setContent {
            LyricoTheme {
                intrinsicSize = painterResource(Res.drawable.ic_album_24dp).intrinsicSize
                // Read through the same resolution path the row uses, so the assertion below is not
                // locale-dependent (the fallback label is translated in all four bundled locales).
                unknownArtist = stringResource(Res.string.unknown_artist)
            }
        }

        runOnIdle {
            assertEquals(Size(24f, 24f), intrinsicSize, "the SVG placeholder did not decode to 24x24")
            assertNotNull(unknownArtist)
        }
    }

    @Test
    fun `a song without an artist falls back to the unknown-artist label`() = runComposeUiTest {
        var unknownArtist: String? = null
        setContent {
            LyricoTheme {
                unknownArtist = stringResource(Res.string.unknown_artist)
                SongListItem(song = song(artist = null), onClick = {})
            }
        }

        runOnIdle { assertNotNull(unknownArtist, "the fallback label could not be resolved") }
        onNodeWithText(unknownArtist!!).assertIsDisplayed()
    }

    @Test
    fun `a click opens the song and a long press starts selection instead`() = runComposeUiTest {
        var opens = 0
        var selections = 0

        setContent {
            LyricoTheme {
                SongListItem(
                    song = song(),
                    onClick = { opens++ },
                    onToggleSelection = { selections++ },
                )
            }
        }

        onNodeWithText("Blue Rondo à la Turk").performClick()
        runOnIdle { assertEquals(1, opens, "a plain click must open the song") }

        onNodeWithText("Blue Rondo à la Turk").performTouchInput { longClick() }
        runOnIdle {
            assertEquals(1, selections, "a long press must start selection")
            assertEquals(1, opens, "a long press must not also open the song")
        }
    }
}
