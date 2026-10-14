package com.lonx.lyrico.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.lonx.lyrico.data.SharedSelectionManager

/**
 * The keyboard half of the desktop selection mode: `Esc` leaves it, `Ctrl+A` takes everything on
 * screen.
 *
 * Android had neither. Selection there was entered by a long press and left by the `X` in the
 * selection top bar, which is what a touch screen offers; on Windows a user reaches for `Esc` and
 * `Ctrl+A` first, and a window where `Esc` does nothing reads as a window that ignores the keyboard.
 *
 * **It is installed as `Window(onKeyEvent = ...)`, not `onPreviewKeyEvent`.** The distinction is the
 * whole reason this class is small enough to be obvious: a preview handler runs *before* the focused
 * component, so `Ctrl+A` inside the search field would select every song in the library instead of the
 * text the user is editing, and `Esc` would leave selection mode instead of dismissing whatever dialog
 * is open. The bubble handler runs only after nothing else claimed the key, so a text field keeps its
 * own `Ctrl+A`, a dialog keeps its own `Esc`, and these two shortcuts only fire when the key really was
 * meant for the window.
 *
 * [visibleUris] is what `Ctrl+A` acts on. It cannot be "every song in the database": selecting songs
 * the user cannot see would make the batch actions (delete, rename) operate on rows that were never on
 * screen, so the list currently being displayed publishes itself instead -- see
 * [SharedSelectionManager.publishVisibleUris].
 */
class SelectionModeShortcuts(private val selectionManager: SharedSelectionManager) {

    /**
     * Returns true when the key was consumed, which is what `Window(onKeyEvent = ...)` expects.
     */
    fun onKeyEvent(event: KeyEvent): Boolean =
        applyShortcut(
            shortcutFor(
                key = event.key,
                type = event.type,
                isCtrlPressed = event.isCtrlPressed,
                isSelectionMode = selectionManager.isSelectionMode.value,
            )
        )

    /**
     * Performs the decision, separated from reading the key so that both halves can be tested: the
     * decision is a pure function of the key and the selection state, and this is the state change it
     * causes. Only the four lines of [onKeyEvent] that unpack a `KeyEvent` are untested, because a
     * `KeyEvent` can only be built from a real `java.awt.event.KeyEvent`.
     */
    internal fun applyShortcut(shortcut: SelectionShortcut?): Boolean =
        when (shortcut) {
            SelectionShortcut.ExitSelectionMode -> {
                selectionManager.exitSelectionMode()
                true
            }

            SelectionShortcut.SelectAllVisible -> {
                selectionManager.selectAll(selectionManager.visibleUris.value.toSet())
                true
            }

            null -> false
        }
}

/** What a key press means while the app is showing a list. */
enum class SelectionShortcut {
    ExitSelectionMode,
    SelectAllVisible,
}

/**
 * The decision on its own, with no window, no manager and no `java.awt` behind it -- `Key` and
 * `KeyEventType` are plain Kotlin values on the desktop, so every combination below is testable in a
 * headless test.
 *
 * Both shortcuts are gated on being in selection mode. `Esc` outside selection mode must stay free for
 * the dialogs and popups that are not part of this flow, and `Ctrl+A` outside it would enter selection
 * mode by selecting everything, which is not what a user pressing it in a library they have not
 * started selecting from is asking for.
 */
fun shortcutFor(
    key: Key,
    type: KeyEventType,
    isCtrlPressed: Boolean,
    isSelectionMode: Boolean,
): SelectionShortcut? {
    if (type != KeyEventType.KeyDown || !isSelectionMode) return null
    return when {
        key == Key.Escape -> SelectionShortcut.ExitSelectionMode
        key == Key.A && isCtrlPressed -> SelectionShortcut.SelectAllVisible
        else -> null
    }
}
