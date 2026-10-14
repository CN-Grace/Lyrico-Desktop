package com.lonx.lyrico.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.lonx.lyrico.data.SharedSelectionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop-only keyboard shortcuts, in two halves.
 *
 * `shortcutFor` is a pure function of `(key, type, ctrl, inSelectionMode)`, so every combination that
 * matters is a one-line assertion here rather than something to be tried by hand in the window. The
 * state change each shortcut causes is asserted against a real [SharedSelectionManager] instead of a
 * mock, because the point of `Ctrl+A` is precisely *which* songs end up selected.
 *
 * `SelectionModeShortcuts.onKeyEvent` itself is not exercised: building a Compose `KeyEvent` needs a
 * real `java.awt.event.KeyEvent`, i.e. a real window. Its two inputs are covered here and its effect on
 * the manager is covered through `applyShortcut`, which is all it does.
 */
class SelectionModeShortcutsTest {

    @Test
    fun `escape in selection mode asks to leave it`() {
        assertEquals(
            SelectionShortcut.ExitSelectionMode,
            shortcutFor(Key.Escape, KeyEventType.KeyDown, isCtrlPressed = false, isSelectionMode = true),
        )
    }

    @Test
    fun `ctrl+a in selection mode asks to select everything on screen`() {
        assertEquals(
            SelectionShortcut.SelectAllVisible,
            shortcutFor(Key.A, KeyEventType.KeyDown, isCtrlPressed = true, isSelectionMode = true),
        )
    }

    @Test
    fun `a plain a is not a shortcut`() {
        assertNull(
            shortcutFor(Key.A, KeyEventType.KeyDown, isCtrlPressed = false, isSelectionMode = true),
            "typing 'a' must never be swallowed by the window handler",
        )
    }

    @Test
    fun `neither shortcut fires outside selection mode`() {
        assertNull(
            shortcutFor(Key.Escape, KeyEventType.KeyDown, isCtrlPressed = false, isSelectionMode = false),
            "escape has to stay free for dialogs and popups when nothing is selected",
        )
        assertNull(
            shortcutFor(Key.A, KeyEventType.KeyDown, isCtrlPressed = true, isSelectionMode = false),
            "ctrl+a must not be a way to enter selection mode",
        )
    }

    @Test
    fun `key releases are ignored`() {
        assertNull(
            shortcutFor(Key.Escape, KeyEventType.KeyUp, isCtrlPressed = false, isSelectionMode = true),
            "a release would fire the shortcut a second time on the way up",
        )
        assertNull(
            shortcutFor(Key.A, KeyEventType.KeyUp, isCtrlPressed = true, isSelectionMode = true),
        )
    }

    @Test
    fun `an unrelated key is not consumed`() {
        assertNull(
            shortcutFor(Key.F5, KeyEventType.KeyDown, isCtrlPressed = false, isSelectionMode = true),
        )
    }

    @Test
    fun `applying the exit shortcut leaves selection mode and reports the key as used`() {
        val manager = SharedSelectionManager()
        manager.selectSwipeRange("b.mp3", listOf("a.mp3", "b.mp3"))
        val shortcuts = SelectionModeShortcuts(manager)

        val consumed = shortcuts.applyShortcut(SelectionShortcut.ExitSelectionMode)

        assertTrue(consumed)
        assertFalse(manager.isSelectionMode.value)
        assertEquals(emptySet(), manager.selectedUris.value)
    }

    @Test
    fun `applying the select-all shortcut takes exactly the published visible songs`() {
        val manager = SharedSelectionManager()
        manager.publishVisibleUris(listOf("visible-1.mp3", "visible-2.mp3"))
        val shortcuts = SelectionModeShortcuts(manager)

        val consumed = shortcuts.applyShortcut(SelectionShortcut.SelectAllVisible)

        assertTrue(consumed)
        assertEquals(setOf("visible-1.mp3", "visible-2.mp3"), manager.selectedUris.value)
        assertTrue(manager.isSelectionMode.value)
    }

    @Test
    fun `select-all on a list that never published selects nothing instead of guessing`() {
        val manager = SharedSelectionManager()
        val shortcuts = SelectionModeShortcuts(manager)

        shortcuts.applyShortcut(SelectionShortcut.SelectAllVisible)

        assertEquals(
            emptySet(),
            manager.selectedUris.value,
            "without a published list the only honest answer is an empty selection, not every song in the database",
        )
    }

    @Test
    fun `no shortcut leaves the key unhandled so the focused component can have it`() {
        val manager = SharedSelectionManager()
        val shortcuts = SelectionModeShortcuts(manager)

        assertFalse(shortcuts.applyShortcut(null))
    }
}
