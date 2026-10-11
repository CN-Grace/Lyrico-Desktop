package com.lonx.lyrico.ui.components.poster

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.lyrico.domain.poster.ArtistPictureEntry
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.label_remove_artist_image
import com.lonx.lyrico.ui.theme.LyricoTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The artist-poster menu's selection state, and the one rendering that depends on it.
 *
 * The state is small but it is where the accidents are. The menu identifies its target by *picture
 * instance*, not by index or by description, because the entry list is rebuilt every time the tag is
 * edited -- an index would silently point at a neighbouring poster after a removal. The tests below say
 * so explicitly: an entry that has left the list resolves to nothing rather than to whoever took its
 * place.
 *
 * [ArtistPosterMenu] itself only renders when a panel is open, so the state tests do the heavy lifting;
 * the rendering test opens the menu and clicks the one action whose target is unambiguous, which is
 * enough to prove the desktop bottom sheet materialises and routes back through the callbacks.
 */
@OptIn(ExperimentalTestApi::class)
class ArtistPosterMenuTest {

    private fun entry(description: String) = ArtistPictureEntry(
        picture = AudioPicture(
            data = byteArrayOf(1),
            mimeType = "image/jpeg",
            description = description,
            pictureType = AudioPictureType.Artist.tagLibName,
        ),
        description = description,
        artist = description,
    )

    @Test
    fun `a fresh menu is closed and has nothing to act on`() {
        val state = ArtistPosterMenuState()

        assertEquals(false, state.showOptions)
        assertNull(state.activeEntry(listOf(entry("A"))), "nothing was clicked yet")
        assertNull(state.pickerRequest)
    }

    @Test
    fun `opening the menu remembers the picture that was clicked`() {
        val target = entry("A")
        val state = ArtistPosterMenuState()

        state.open(target)

        assertTrue(state.showOptions)
        assertSame(target, state.activeEntry(listOf(entry("B"), target)))
    }

    @Test
    fun `closing the menu keeps the target, because the action runs after it closes`() {
        val target = entry("A")
        val state = ArtistPosterMenuState()

        state.open(target)
        state.closeOptions()

        assertEquals(false, state.showOptions)
        assertSame(target, state.activeEntry(listOf(target)))
    }

    @Test
    fun `a picture that has left the list is no longer a target`() {
        val state = ArtistPosterMenuState()
        state.open(entry("A"))

        val remaining = listOf(entry("B"))

        assertNull(
            state.activeEntry(remaining),
            "the list was rebuilt from the tag; an entry with the same description is a different picture",
        )
    }

    @Test
    fun `the menu can be opened without a target, falling back to the current page`() {
        val state = ArtistPosterMenuState()

        state.open(null)

        assertTrue(state.showOptions)
        assertNull(state.activeEntry(listOf(entry("A"))))
    }

    @Test
    fun `the picker request records what the choice is for`() {
        val state = ArtistPosterMenuState()

        state.ask(PosterPickerRequest.Remove)
        assertEquals(PosterPickerRequest.Remove, state.pickerRequest)

        state.dismissPicker()
        assertNull(state.pickerRequest)
    }

    @Test
    fun `choosing an artist and choosing an existing picture are different requests`() {
        assertTrue(PosterPickerRequest.SetPoster.picksArtist)
        assertTrue(PosterPickerRequest.Reassign(entry("A").picture).picksArtist)
        assertEquals(false, PosterPickerRequest.Remove.picksArtist)
        assertEquals(false, PosterPickerRequest.Export.picksArtist)
        assertEquals(false, PosterPickerRequest.Crop.picksArtist)
    }

    @Test
    fun `a reassign request carries the picture it is about`() {
        val picture = entry("A").picture

        val request = PosterPickerRequest.Reassign(picture)

        assertSame(picture, request.target)
    }

    @Test
    fun `an opened menu shows its actions and routes the chosen one`() = runComposeUiTest {
        val state = ArtistPosterMenuState()
        val target = entry("A")
        var removed: AudioPicture? = null
        var removeLabel = ""
        setContent {
            LyricoTheme {
                removeLabel = stringResource(Res.string.label_remove_artist_image)
                ArtistPosterMenu(
                    state = state,
                    artists = listOf("A"),
                    entries = listOf(target),
                    currentEntry = target,
                    currentPageIsArtistSlot = true,
                    onSetPoster = {},
                    onRemove = { removed = it },
                    onExport = {},
                    onCrop = {},
                    onReassign = { _, _ -> },
                    onMissingArtist = {},
                )
            }
        }

        // Nothing is open yet: the panel renders only when the state says so, and the entry has to be
        // the one the menu was opened on for the remove action to be offered at all.
        onNodeWithText(removeLabel).assertDoesNotExist()

        runOnIdle { state.open(target) }

        onNodeWithText(removeLabel).assertIsDisplayed()
        onNodeWithText(removeLabel).performClick()

        runOnIdle {
            val picture = assertNotNull(removed, "the menu did not route the remove action")
            assertSame(target.picture, picture, "the action must run on the picture the menu was opened on")
        }
    }
}
