package com.lonx.lyrico.ui

import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EscapeBackKey] against the real dispatcher rather than a mock: the whole point of the class is what
 * the dispatcher does with the event it raises, so a fake would test nothing.
 *
 * Two rules are pinned here, and both matter for `Esc` behaving like a desktop user expects:
 *
 * - **It only fires when something is listening.** Otherwise the key would have to be consumed
 *   unconditionally, and `Esc` on a screen with no overlay would stop meaning "leave selection mode".
 * - **A registered handler receives exactly one completed back event.** Miuix's sheets and dialogs
 *   dismiss themselves from that callback, so a dropped event is a sheet that cannot be closed with the
 *   keyboard -- which is what the desktop had before this class existed.
 */
class EscapeBackKeyTest {

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

    @Test
    fun `pressing escape with nothing listening reports that it was not handled`() {
        val input = EscapeBackKey()
        input.attachTo(NavigationEventDispatcher())

        assertFalse(
            "no handler means the key is still free for the window's own Esc shortcut",
            input.press(),
        )
    }

    @Test
    fun `pressing escape delivers one completed back event to the registered handler`() {
        val dispatcher = NavigationEventDispatcher()
        val input = EscapeBackKey()
        input.attachTo(dispatcher)
        val handler = RecordingHandler()
        dispatcher.addHandler(handler)

        assertTrue("the handler owns the key now", input.press())

        assertEquals(1, handler.backCount)
    }

    @Test
    fun `detaching the input stops it from reaching the handler`() {
        val dispatcher = NavigationEventDispatcher()
        val input = EscapeBackKey()
        val detach = input.attachTo(dispatcher)
        val handler = RecordingHandler()
        dispatcher.addHandler(handler)
        assertTrue(input.press())

        detach()

        assertFalse("an unregistered input must not dispatch", input.press())
        assertEquals(1, handler.backCount)
    }

    @Test
    fun `removing the handler makes escape unhandled again`() {
        val dispatcher = NavigationEventDispatcher()
        val input = EscapeBackKey()
        input.attachTo(dispatcher)
        val handler = RecordingHandler()
        dispatcher.addHandler(handler)
        assertTrue(input.press())

        handler.remove()

        assertFalse("the sheet is gone, so Esc belongs to the window again", input.press())
        assertEquals(1, handler.backCount)
    }
}
