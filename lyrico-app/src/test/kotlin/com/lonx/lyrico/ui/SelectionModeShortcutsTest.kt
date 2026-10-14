package com.lonx.lyrico.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
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
 * The second half drives `SelectionModeShortcuts.onKeyEvent` through a real `KeyEvent`. That was once
 * believed impossible without a window -- building one from a `java.awt.event.KeyEvent` does need AWT --
 * but the desktop source set has a `KeyEvent(key, type, isCtrlPressed = ...)` factory, so the three
 * fields the class reads can be handed over directly, headlessly, and the *unpacking* is covered too.
 * That distinction is not academic: a test of `shortcutFor` alone stays green while `Ctrl+A` arrives as
 * a plain `A`, which is the failure worth catching.
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

    @Test
    fun `ctrl+a through a key event selects every song on screen`() {
        val manager = managerWith("a", "b", "c")
        manager.toggle("a")
        val shortcuts = SelectionModeShortcuts(manager)

        val consumed = shortcuts.onKeyEvent(keyEvent(Key.A, isCtrlPressed = true))

        assertTrue(consumed, "Ctrl+A belongs to the window while a list is being selected")
        assertEquals(setOf("a", "b", "c"), manager.selectedUris.value)
    }

    @Test
    fun `a plain a through a key event is left alone`() {
        val manager = managerWith("a", "b", "c")
        manager.toggle("a")
        val shortcuts = SelectionModeShortcuts(manager)

        val consumed = shortcuts.onKeyEvent(keyEvent(Key.A))

        assertFalse(consumed, "typing a is not selecting everything")
        assertEquals(setOf("a"), manager.selectedUris.value)
    }

    @Test
    fun `escape through a key event leaves selection mode`() {
        val manager = managerWith("a", "b", "c")
        manager.toggle("a")
        val shortcuts = SelectionModeShortcuts(manager)

        val consumed = shortcuts.onKeyEvent(keyEvent(Key.Escape))

        assertTrue(consumed, "Esc is the desktop way out of selection mode")
        assertFalse(manager.isSelectionMode.value)
    }

    @Test
    fun `escape goes to the open sheet before it leaves selection mode`() {
        val manager = managerWith("a", "b", "c")
        manager.toggle("a")
        val dispatcher = NavigationEventDispatcher()
        val escapeBackKey = EscapeBackKey()
        escapeBackKey.attachTo(dispatcher)
        val handler = RecordingHandler()
        dispatcher.addHandler(handler)
        val shortcuts = SelectionModeShortcuts(manager, escapeBackKey)

        val consumed = shortcuts.onKeyEvent(keyEvent(Key.Escape))

        assertTrue(consumed, "the sheet owns the key")
        assertEquals(1, handler.backCount, "the sheet was asked to dismiss itself")
        assertTrue(manager.isSelectionMode.value, "and the list behind it is still being selected from")
    }

    @Test
    fun `a key up through a key event is not a shortcut`() {
        val manager = managerWith("a", "b", "c")
        manager.toggle("a")
        val shortcuts = SelectionModeShortcuts(manager)

        val consumed = shortcuts.onKeyEvent(keyEvent(Key.Escape, type = KeyEventType.KeyUp))

        assertFalse(consumed, "only the press acts, or the key would fire twice")
        assertTrue(manager.isSelectionMode.value)
    }

    /** The dispatcher only carries info about where a back request came from; nothing reads it here. */
    private class TestInfo : NavigationEventInfo()

    private class RecordingHandler : NavigationEventHandler<NavigationEventInfo>(
        initialInfo = TestInfo(),
        isBackEnabled = true,
    ) {
        var backCount = 0

        override fun onBackCompleted() {
            backCount++
        }
    }

    /**
     * `@InternalComposeUiApi`, and the opt-in is deliberate: it is the only way to hand the class a
     * `KeyEvent` at all. The value-class constructor takes an `InternalKeyEvent`, and every other route
     * -- `java.awt.event.KeyEvent` included -- ends in a cast to that same internal type. Reaching one
     * level into the framework to cover the window's key handling is worth more than the shortcuts going
     * unverified, and the opt-in stays confined to this helper.
     */
    @OptIn(InternalComposeUiApi::class)
    private fun keyEvent(
        key: Key,
        type: KeyEventType = KeyEventType.KeyDown,
        isCtrlPressed: Boolean = false,
    ) = KeyEvent(key = key, type = type, isCtrlPressed = isCtrlPressed)

    private fun managerWith(vararg visible: String) = SharedSelectionManager().apply {
        publishVisibleUris(visible.toList())
    }
}
