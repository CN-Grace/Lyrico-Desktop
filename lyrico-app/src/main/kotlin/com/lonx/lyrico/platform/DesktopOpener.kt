package com.lonx.lyrico.platform

import java.awt.Desktop
import java.awt.HeadlessException
import java.io.File

/**
 * The hand-off to the operating system's shell, isolated behind an interface.
 *
 * Two reasons this is not just `Desktop.getDesktop()` called inline: the tests must be able to assert
 * what gets handed over **without launching a media player on the machine running them**, and the
 * shell's availability is a question with more than one answer on Windows (no desktop session at all,
 * a session that does not support the OPEN action, a headless JVM).
 */
interface DesktopOpener {

    /** Whether this environment can hand a file to a program at all. */
    fun isSupported(): Boolean

    /**
     * Asks the shell to open [file] with whatever program is associated with it.
     *
     * [file] must be absolute — `java.awt.Desktop` rejects a relative path with
     * `IllegalArgumentException` rather than resolving it — and the implementation is expected to
     * throw when the shell refuses, so the caller can tell "opened" from "did not open".
     */
    fun open(file: File)
}

/** The real thing: `java.awt.Desktop` in OPEN mode, which is what a double-click in Explorer does. */
class AwtDesktopOpener : DesktopOpener {

    override fun isSupported(): Boolean = try {
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
    } catch (_: HeadlessException) {
        // No desktop session to ask. Compose Desktop needs one to show a window at all, so this only
        // happens to a process that is not the app (a headless test JVM, a service).
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    override fun open(file: File) {
        Desktop.getDesktop().open(file)
    }
}
