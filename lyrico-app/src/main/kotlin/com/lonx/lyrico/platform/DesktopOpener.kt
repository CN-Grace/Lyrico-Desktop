package com.lonx.lyrico.platform

import java.awt.Desktop
import java.net.URI
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

    /**
     * Asks the shell to open [url] in the user's default browser.
     *
     * Separate from [open] because `java.awt.Desktop` treats them as two different actions (OPEN vs
     * BROWSE) and either can be unsupported on its own; a desktop with no browser is not a desktop that
     * cannot open a file. Added for the about page, whose Android version started an `ACTION_VIEW`
     * intent -- the same call the settings screen's project links need.
     */
    fun browse(url: String)
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

    override fun browse(url: String) {
        Desktop.getDesktop().browse(URI(url))
    }
}
