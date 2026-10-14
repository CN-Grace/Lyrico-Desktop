package com.lonx.lyrico.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Selection state, exercised against the real object rather than a mock.
 *
 * This class is portable verbatim from Android -- no `Context`, no `Uri`, just three `StateFlow`s over
 * `String` keys -- so the only thing worth asserting is the state machine itself. Three rules here are
 * easy to get wrong when the class is only skimmed, and all three are pinned below:
 *
 * - **Toggling the last song off leaves selection mode on.** `toggle` sets the mode unconditionally, so
 *   the toolbar stays up at a zero count: deselecting everything is *not* an exit; only
 *   `exitSelectionMode()` (or `clearAll()`) clears it. Anything that infers "empty selection means mode
 *   off" would leave the toolbar up forever.
 * - **The anchor is the last row clicked, not the last row selected.** `toggle` moves it even when the
 *   click removed a song, which is what makes ctrl+click then shift+click select a run -- and what lets
 *   the long press that opens selection mode anchor the swipe drag that follows it.
 * - **A completed range swipe clears the anchor; a fallback swipe sets it.** `selectSwipeRange` nulls
 *   `swipeAnchorUri` once it has selected a contiguous range, and points it at the target whenever it
 *   could not (no anchor, anchor not selected, anchor or target outside the visible window). That is
 *   what makes a drag extend from the origin and a *second* drag start fresh instead of re-extending
 *   from the original song.
 */
class SharedSelectionManagerTest {

    private val visible = listOf("a.mp3", "b.mp3", "c.mp3", "d.mp3")

    @Test
    fun `toggling a uri selects it and enters selection mode`() {
        val manager = SharedSelectionManager()

        assertFalse(manager.isSelectionMode.value, "selection mode must start off")

        manager.toggle("a.mp3")

        assertEquals(setOf("a.mp3"), manager.selectedUris.value)
        assertTrue(manager.isSelectionMode.value)
    }

    @Test
    fun `toggling the same uri again removes it but leaves selection mode on`() {
        val manager = SharedSelectionManager()
        manager.toggle("a.mp3")

        manager.toggle("a.mp3")

        assertEquals(emptySet(), manager.selectedUris.value)
        assertTrue(
            manager.isSelectionMode.value,
            "toggling the last song off is not an exit; only exitSelectionMode clears the mode",
        )
    }

    @Test
    fun `select all replaces the selection with every given uri`() {
        val manager = SharedSelectionManager()
        manager.toggle("stale.mp3")

        manager.selectAll(setOf("a.mp3", "b.mp3"))

        assertEquals(setOf("a.mp3", "b.mp3"), manager.selectedUris.value)
        assertTrue(manager.isSelectionMode.value)
    }

    @Test
    fun `deselect all empties the selection and the anchor but leaves selection mode on`() {
        val manager = SharedSelectionManager()
        manager.selectSwipeRange("b.mp3", visible)

        manager.deselectAll()

        assertEquals(emptySet(), manager.selectedUris.value)
        assertNull(manager.swipeAnchorUri.value)
        assertTrue(manager.isSelectionMode.value, "the toolbar stays up with a zero count")
    }

    @Test
    fun `setting an empty uri set does not enter selection mode`() {
        val manager = SharedSelectionManager()

        manager.setUris(emptySet())

        assertEquals(emptySet(), manager.selectedUris.value)
        assertFalse(manager.isSelectionMode.value)
    }

    @Test
    fun `the first swipe selects the song and remembers it as the anchor`() {
        val manager = SharedSelectionManager()

        manager.selectSwipeRange("b.mp3", visible)

        assertEquals(setOf("b.mp3"), manager.selectedUris.value)
        assertEquals("b.mp3", manager.swipeAnchorUri.value)
        assertTrue(manager.isSelectionMode.value)
    }

    @Test
    fun `swiping forward from the anchor selects the range and clears the anchor`() {
        val manager = SharedSelectionManager()
        manager.selectSwipeRange("b.mp3", visible)

        manager.selectSwipeRange("d.mp3", visible)

        assertEquals(setOf("b.mp3", "c.mp3", "d.mp3"), manager.selectedUris.value)
        assertNull(
            manager.swipeAnchorUri.value,
            "a finished range swipe must re-anchor, or the next drag would extend from b again",
        )
    }

    @Test
    fun `swiping backwards selects the range in the other direction`() {
        val manager = SharedSelectionManager()
        manager.selectSwipeRange("c.mp3", visible)

        manager.selectSwipeRange("a.mp3", visible)

        assertEquals(setOf("a.mp3", "b.mp3", "c.mp3"), manager.selectedUris.value)
    }

    @Test
    fun `swiping onto an already selected song keeps the selection and re-anchors there`() {
        val manager = SharedSelectionManager()
        manager.selectSwipeRange("b.mp3", visible)

        manager.selectSwipeRange("b.mp3", visible)

        assertEquals(setOf("b.mp3"), manager.selectedUris.value)
        assertEquals("b.mp3", manager.swipeAnchorUri.value)
    }

    @Test
    fun `swiping against a list that does not contain the anchor adds the target alone`() {
        val manager = SharedSelectionManager()
        manager.selectSwipeRange("gone.mp3", listOf("gone.mp3", "x.mp3"))

        // The anchor is now outside the window: the range cannot be computed, so the target is added
        // and becomes the new anchor rather than selecting whatever happens to sit between them.
        manager.selectSwipeRange("b.mp3", visible)

        assertEquals(setOf("gone.mp3", "b.mp3"), manager.selectedUris.value)
        assertEquals("b.mp3", manager.swipeAnchorUri.value)
    }

    @Test
    fun `replacing the selection with an anchor-less set clears the stale anchor`() {
        val manager = SharedSelectionManager()
        manager.selectSwipeRange("b.mp3", visible)
        assertEquals("b.mp3", manager.swipeAnchorUri.value)

        manager.setUris(setOf("a.mp3"))

        assertEquals(setOf("a.mp3"), manager.selectedUris.value)
        assertNull(
            manager.swipeAnchorUri.value,
            "an anchor that is no longer selected would make the next swipe select from a hidden song",
        )
    }

    @Test
    fun `renaming a selection rewrites the selected keys and the anchor`() {
        val manager = SharedSelectionManager()
        manager.toggle("old.mp3")
        manager.setUris(setOf("old.mp3", "other.mp3"))

        manager.replaceUris(mapOf("old.mp3" to "new.mp3"))

        assertEquals(setOf("new.mp3", "other.mp3"), manager.selectedUris.value)
    }

    @Test
    fun `renaming with an empty mapping changes nothing`() {
        val manager = SharedSelectionManager()
        manager.toggle("a.mp3")

        manager.replaceUris(emptyMap())

        assertEquals(setOf("a.mp3"), manager.selectedUris.value)
        assertTrue(manager.isSelectionMode.value)
    }

    @Test
    fun `exiting selection mode clears the selection, the mode and the anchor`() {
        val manager = SharedSelectionManager()
        manager.selectSwipeRange("b.mp3", visible)

        manager.exitSelectionMode()

        assertEquals(emptySet(), manager.selectedUris.value)
        assertFalse(manager.isSelectionMode.value)
        assertNull(manager.swipeAnchorUri.value)
    }

    @Test
    fun `clear all is the same exit as exit selection mode`() {
        val manager = SharedSelectionManager()
        manager.selectSwipeRange("b.mp3", visible)

        manager.clearAll()

        assertEquals(emptySet(), manager.selectedUris.value)
        assertFalse(manager.isSelectionMode.value)
        assertNull(manager.swipeAnchorUri.value)
    }

    @Test
    fun `the first shift click selects the song and anchors there`() {
        val manager = SharedSelectionManager()

        manager.extendRange("b.mp3", visible)

        assertEquals(setOf("b.mp3"), manager.selectedUris.value)
        assertEquals("b.mp3", manager.swipeAnchorUri.value)
        assertTrue(manager.isSelectionMode.value)
    }

    @Test
    fun `shift clicking a second row selects the range and keeps the anchor`() {
        val manager = SharedSelectionManager()
        manager.extendRange("b.mp3", visible)

        manager.extendRange("d.mp3", visible)

        assertEquals(setOf("b.mp3", "c.mp3", "d.mp3"), manager.selectedUris.value)
        assertEquals(
            "b.mp3",
            manager.swipeAnchorUri.value,
            "shift+click is not a one-shot drag: the next click must measure from the same anchor",
        )
    }

    @Test
    fun `shift clicking back towards the anchor shrinks the range instead of adding to it`() {
        val manager = SharedSelectionManager()
        manager.extendRange("a.mp3", visible)
        manager.extendRange("d.mp3", visible)
        assertEquals(setOf("a.mp3", "b.mp3", "c.mp3", "d.mp3"), manager.selectedUris.value)

        manager.extendRange("b.mp3", visible)

        assertEquals(
            setOf("a.mp3", "b.mp3"),
            manager.selectedUris.value,
            "the range replaces the selection, as Explorer does; a union could only ever grow",
        )
        assertEquals("a.mp3", manager.swipeAnchorUri.value)
    }

    @Test
    fun `shift clicking an anchor that left the list selects the target alone and re-anchors`() {
        val manager = SharedSelectionManager()
        manager.extendRange("gone.mp3", listOf("gone.mp3", "x.mp3"))

        manager.extendRange("c.mp3", visible)

        assertEquals(setOf("c.mp3"), manager.selectedUris.value)
        assertEquals("c.mp3", manager.swipeAnchorUri.value)
    }

    @Test
    fun `shift clicking the anchor itself selects only the anchor`() {
        val manager = SharedSelectionManager()
        manager.extendRange("a.mp3", visible)
        manager.extendRange("d.mp3", visible)

        manager.extendRange("a.mp3", visible)

        assertEquals(setOf("a.mp3"), manager.selectedUris.value)
        assertEquals("a.mp3", manager.swipeAnchorUri.value)
    }

    @Test
    fun `a ctrl click moves the shift anchor, as Explorer does`() {
        val manager = SharedSelectionManager()
        manager.extendRange("b.mp3", visible)
        manager.extendRange("c.mp3", visible)

        manager.toggle("a.mp3")

        assertEquals(setOf("a.mp3", "b.mp3", "c.mp3"), manager.selectedUris.value)
        assertEquals(
            "a.mp3",
            manager.swipeAnchorUri.value,
            "the anchor follows the last row clicked, so the next shift+click measures from the ctrl+click",
        )
    }

    @Test
    fun `a ctrl click followed by a shift click selects the run between them`() {
        val manager = SharedSelectionManager()

        manager.toggle("b.mp3")
        manager.extendRange("d.mp3", visible)

        assertEquals(
            setOf("b.mp3", "c.mp3", "d.mp3"),
            manager.selectedUris.value,
            "ctrl+click then shift+click is the ordinary desktop way to select a run",
        )
        assertEquals("b.mp3", manager.swipeAnchorUri.value)
    }

    @Test
    fun `a ctrl click that deselects still moves the shift anchor`() {
        val manager = SharedSelectionManager()
        manager.toggle("b.mp3")
        manager.toggle("c.mp3")

        manager.toggle("c.mp3")

        assertEquals(setOf("b.mp3"), manager.selectedUris.value)
        assertEquals(
            "c.mp3",
            manager.swipeAnchorUri.value,
            "Explorer keeps the anchor on the row clicked, whether that click selected or deselected it",
        )

        manager.extendRange("b.mp3", visible)

        assertEquals(
            setOf("b.mp3", "c.mp3"),
            manager.selectedUris.value,
            "a deselected anchor is still a valid place to measure a shift+click range from",
        )
    }

    @Test
    fun `the long press that starts selection mode anchors the swipe`() {
        val manager = SharedSelectionManager()

        // The long press enters selection mode through `toggle`, and the drag that follows it goes
        // through `selectSwipeRange`. Both halves have to agree on the anchor or the drag selects
        // nothing but the row it started on.
        manager.toggle("b.mp3")
        manager.selectSwipeRange("d.mp3", visible)

        assertEquals(
            setOf("b.mp3", "c.mp3", "d.mp3"),
            manager.selectedUris.value,
            "a swipe after a long press must select the run, not just the row dragged onto",
        )
    }
}
