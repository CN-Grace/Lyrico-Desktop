package com.lonx.lyrico.support

import com.lonx.lyrico.utils.logging.PlatformLog
import java.util.Collections

/**
 * Captures what the ported code writes to [PlatformLog] so a test can assert on it.
 *
 * `PlatformLog.sink` replaced Android's logcat with a plain sink *precisely so* a test can observe
 * diagnostics, which matters for the one place the desktop port is deliberately less capable than
 * Android: navigations to screens that are not ported yet are logged and dropped instead of crashing
 * the window. A test that did not read the log would only be able to say "it didn't crash", which is
 * the weaker half of the claim.
 *
 * It is a class, not an object, because the sink is process-global: the instance that installs itself
 * is the instance that removes itself in the test's teardown.
 */
class PlatformLogCapture {

    data class Line(val level: Char, val tag: String, val message: String)

    private val logged: MutableList<Line> = Collections.synchronizedList(mutableListOf())

    val lines: List<Line> get() = synchronized(logged) { logged.toList() }

    val warnings: List<String>
        get() = lines.filter { it.level == PlatformLog.WARN }.map { it.message }

    /** Replaces the sink for the lifetime of this capture. */
    fun install(): PlatformLogCapture {
        synchronized(logged) { logged.clear() }
        PlatformLog.sink = { level, tag, message, _ ->
            logged += Line(level, tag, message)
        }
        return this
    }

    /** Puts the default sink back, so nothing leaks into the rest of the JVM. */
    fun restore() {
        PlatformLog.resetSink()
    }
}
