package com.lonx.lyrico.data.repository

import com.lonx.lyrico.platform.AwtDesktopOpener
import com.lonx.lyrico.platform.DesktopOpener
import java.nio.file.Files
import java.nio.file.Path

/**
 * Handing a song over to whatever program Windows has associated with it.
 *
 * Android had four ways to do this — a bare `ACTION_VIEW`, `ACTION_VIEW` pinned to a package,
 * `ACTION_CHOOSER` and the plain default app — because Android lets an app enumerate the players and
 * ask the user to pick one. Windows has no such in-process picker, and the desktop decision is to use
 * the system association only (the equivalent of double-clicking the file in Explorer), so the other
 * three are gone rather than emulated:
 *
 * - targeting a package: no equivalent without enumerating the registry;
 * - the chooser: `Desktop.open` always uses the current default, and Windows' own "Open with" dialog
 *   is reachable from Explorer, not from here.
 *
 * The two Android `Toast`s are gone with them: a repository should not draw, so every outcome is
 * returned and the UI decides what to show (P4).
 */
interface PlaybackRepository {

    /**
     * Opens [path] with the program Windows is associated with.
     *
     * Synchronous, as the Android version was — the shell call returns once the program has been
     * started. Nothing is opened when the path is not an existing regular file.
     */
    fun open(path: Path): PlaybackResult
}

sealed interface PlaybackResult {

    /** The shell was asked to open the file and accepted the request. */
    data object Opened : PlaybackResult

    /** [path] does not exist, or is a directory: there is nothing to hand over. */
    data class FileUnavailable(val path: Path) : PlaybackResult

    /**
     * This environment cannot open files at all — no desktop session, or a session that does not
     * support the OPEN action. The UI should say so instead of retrying.
     */
    data object Unsupported : PlaybackResult

    /**
     * The shell refused. This covers both "no program is associated with this file type" and a real
     * failure; `java.awt.Desktop` reports them through the same `IOException`, so they cannot be told
     * apart here and the UI should present the throwable's message.
     */
    data class Failed(val path: Path, val throwable: Throwable) : PlaybackResult
}

class PlaybackRepositoryImpl(
    private val desktopOpener: DesktopOpener = AwtDesktopOpener(),
) : PlaybackRepository {

    override fun open(path: Path): PlaybackResult {
        if (!desktopOpener.isSupported()) return PlaybackResult.Unsupported

        val absolute = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(absolute)) return PlaybackResult.FileUnavailable(absolute)

        return try {
            // `Desktop.open` rejects a relative path rather than resolving it, hence the absolute one.
            desktopOpener.open(absolute.toFile())
            PlaybackResult.Opened
        } catch (e: Exception) {
            PlaybackResult.Failed(absolute, e)
        }
    }
}
