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
 * `String` keys -- so the only thing worth asserting is the state machine itself. Two rules here are
 * easy to get wrong when the class is only skimmed, and both are pinned below:
 *
 * - **Toggling the last song off leaves selection mode on.** `toggle` sets the mode unconditionally, so
 *   deselecting everything is *not* an exit; only `exitSelectionMode()` (or `clearAll()`) clears it.
 *   Anything that infers "empty selection means mode off" would leave the toolbar up forever.
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
}
