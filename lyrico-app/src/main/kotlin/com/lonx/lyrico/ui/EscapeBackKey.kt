package com.lonx.lyrico.ui

import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventInput

/**
 * The half of `Esc` that belongs to the overlays rather than to the app.
 *
 * Compose Multiplatform routes a back request to whatever registered for one -- Miuix's sheets and
 * dialogs do, and so does the navigation stack -- but on the desktop nothing ever *raises* one: there
 * is no system back gesture to press, and `Esc` is not wired to the dispatcher by the framework. That
 * leaves `Esc` with nothing to do for an open sheet, so the menu could only be closed with the mouse,
 * and [SelectionModeShortcuts] would leave selection mode while the dialog in front of it stayed up.
 *
 * This is the missing half: a back input the window can fire from `Esc`.
 *
 * It stays quiet unless something is actually listening, which is what keeps `Esc` on a screen with no
 * overlay from popping the navigation stack. [press] reports whether it had a handler to deliver to,
 * and the caller falls through to its own `Esc` behaviour when it did not.
 */
class EscapeBackKey : NavigationEventInput() {

    private var isAttached = false
    private var hasEnabledHandlers = false

    override fun onAdded(dispatcher: NavigationEventDispatcher) {
        isAttached = true
    }

    override fun onRemoved() {
        isAttached = false
        hasEnabledHandlers = false
    }

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        this.hasEnabledHandlers = hasEnabledHandlers
    }

    /**
     * Delivers one completed back request to the topmost handler, or does nothing and returns false
     * when this input is detached or no handler is registered.
     *
     * The two guards are not the same check: a detached input still reports the handler count it last
     * saw, and dispatching from it throws rather than doing nothing.
     */
    fun press(): Boolean {
        if (!isAttached || !hasEnabledHandlers) return false
        dispatchOnBackCompleted()
        return true
    }

    /**
     * Registers this input with [dispatcher] and returns the matching unregister, for a
     * `DisposableEffect` to call on the way out. Calling the returned function twice is harmless.
     */
    fun attachTo(dispatcher: NavigationEventDispatcher): () -> Unit {
        dispatcher.addInput(this)
        return {
            if (isAttached) dispatcher.removeInput(this)
        }
    }
}
