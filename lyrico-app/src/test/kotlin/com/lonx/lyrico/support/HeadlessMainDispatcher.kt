package com.lonx.lyrico.support

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Points `Dispatchers.Main` at [Dispatchers.Unconfined] for a headless test whose clicks write lifecycles.
 *
 * `androidx.lifecycle` refuses a lifecycle write from anything but the main dispatcher's thread, and that
 * refusal is invisible in the worst way. A click handled by `runComposeUiTest` runs on the Gradle test
 * worker thread (`Test worker @kotlinx.coroutines.test runner`), while the harness tears its scene down on
 * the Swing event thread -- which is what `Dispatchers.Main` resolves to on desktop. Navigating from the
 * click therefore throws `Method setCurrentState must be called on the main thread` inside
 * `NavBackStackEntryImpl.updateState`, and the throw leaves the new back-stack entry at `INITIALIZED`.
 * The failure then surfaces one step later and somewhere else entirely: `closeScene` moves the host
 * lifecycle to `DESTROYED`, reaches that entry, and fails as
 * `State must be at least 'CREATED' to be moved to 'DESTROYED'` -- an error that reads like a navigation
 * bug but is a test-thread bug.
 *
 * What gets installed is a tiny **inline** dispatcher ([InlineMainDispatcher]) rather than
 * `Dispatchers.Unconfined` or a test dispatcher. All three have no thread of their own, which is what the
 * check above needs -- but the other two answer `isDispatchNeeded() == true`, so
 * `TestMainDispatcher.immediate` (which is exactly what `viewModelScope` uses) calls `dispatch` on them, and
 * both of their `dispatch` implementations exist only to throw
 * `UnsupportedOperationException: ... dispatch can only be used by the yield function`. That surfaced as an
 * occasional failure in whichever screen test happened to run a view model's `init` at that moment -- one of
 * the flakes this suite used to have. `isDispatchNeeded() == false` means such a coroutine simply runs on the
 * calling thread, which is the property this helper is after.
 *
 * Production is unaffected and needs no equivalent: a real window has exactly one main dispatcher -- the
 * Swing event thread -- and the navigation it performs already happens there.
 *
 * It is a class, not an object, because `Dispatchers.Main` is process-global: the instance that installs
 * itself is the instance that restores it in the test's teardown.
 */
class HeadlessMainDispatcher {

    /** Replaces `Dispatchers.Main` for the lifetime of this installation. */
    fun install(): HeadlessMainDispatcher {
        Dispatchers.setMain(InlineMainDispatcher)
        return this
    }

    /** Puts the real main dispatcher back, so nothing leaks into the rest of the JVM. */
    fun restore() {
        Dispatchers.resetMain()
    }
}

/**
 * A `Dispatchers.Main` replacement with no thread of its own: it never asks to be dispatched to, so
 * coroutines that target it (including `Dispatchers.Main.immediate`, which `viewModelScope` uses) run on
 * whichever thread resumes them.
 *
 * `dispatch` has to be implemented because the interface requires it; it is never reached while
 * [isDispatchNeeded] answers `false`.
 */
private object InlineMainDispatcher : CoroutineDispatcher() {

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
}
