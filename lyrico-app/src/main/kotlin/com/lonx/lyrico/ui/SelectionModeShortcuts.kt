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
 * own `Ctrl+A`, and these two shortcuts only fire when the key really was meant for the window.
 *
 * The dialog half of that contract needs [EscapeBackKey]: "a dialog keeps its own `Esc`" is only true if
 * something hands the key to the dialog, and on the desktop nothing does until the window raises the
 * back request itself.
 *
 * [visibleUris] is what `Ctrl+A` acts on. It cannot be "every song in the database": selecting songs
 * the user cannot see would make the batch actions (delete, rename) operate on rows that were never on
 * screen, so the list currently being displayed publishes itself instead -- see
 * [SharedSelectionManager.publishVisibleUris].
 */
class SelectionModeShortcuts(
    private val selectionManager: SharedSelectionManager,
    private val escapeBackKey: EscapeBackKey? = null,
) {

    /**
     * Returns true when the key was consumed, which is what `Window(onKeyEvent = ...)` expects.
     *
     * `Esc` is offered to the overlay layer first: a sheet or dialog in front of the list owns the key,
     * and only when nothing is listening does it mean "leave selection mode". Without that order the
     * shortcut would fire behind an open menu, which is the one thing the bubble handler was chosen to
     * prevent.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyDown &&
            event.key == Key.Escape &&
            escapeBackKey?.press() == true
        ) {
            return true
        }

        return applyShortcut(
            shortcutFor(
                key = event.key,
                type = event.type,
                isCtrlPressed = event.isCtrlPressed,
                isSelectionMode = selectionManager.isSelectionMode.value,
            )
        )
    }

    /**
     * Performs the decision, separated from reading the key so that both halves can be tested: the
     * decision is a pure function of the key and the selection state, and this is the state change it
     * causes. Both halves are covered in `SelectionModeShortcutsTest` -- the decision directly, and
     * [onKeyEvent] through a real `KeyEvent` built with the desktop source set's own factory.
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
