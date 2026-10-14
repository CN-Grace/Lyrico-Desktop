package com.lonx.lyrico.platform

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Rectangle
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where the window was when the user last closed it, in density-independent pixels.
 *
 * The unit is dp and not pixels because that is the unit [androidx.compose.ui.window.WindowState]
 * reports and accepts: storing what Compose hands over keeps the round trip lossless on a scaled
 * display, where a window at 150% would otherwise move a little every time the app started.
 */
@Serializable
data class WindowGeometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Remembers the window's size and position across runs.
 *
 * Android had no equivalent -- an `Activity` is handed a window by the system, and the user's resizing
 * of a phone screen is not a thing. On Windows a resized, moved window is part of how the app is used,
 * and a port that opens at a hard-coded 1180x780 every time looks like it forgot the user's work.
 *
 * Every failure is a fallback to "no saved geometry", never an exception: the file can be missing on a
 * first run, truncated by a crash mid-write, or hand-edited into nonsense, and none of those is a
 * reason to refuse to open the app. The caller treats `null` as "use the default size and let Windows
 * place the window".
 */
class WindowGeometryStore(private val file: File) {

    private val json = Json {
        // A future version may add fields; an older build must still read the file rather than throw
        // its settings away.
        ignoreUnknownKeys = true
    }

    /** The saved geometry, or null when there is nothing usable to restore. */
    fun load(): WindowGeometry? = runCatching {
        if (!file.isFile) return null
        val geometry = json.decodeFromString<WindowGeometry>(file.readText())
        // A zero or negative size is what a half-written or hand-edited file looks like, and passing it
        // to `rememberWindowState` would produce a window nobody can resize back.
        if (geometry.width <= 0 || geometry.height <= 0) null else geometry
    }.getOrNull()

    /**
     * Writes [geometry] for the next run, replacing the file atomically.
     *
     * The temporary file plus move is the same pattern [com.lonx.lyrico.data.song.file.AudioFileAccess]
     * uses: a crash while writing leaves the old file intact instead of a truncated one, which matters
     * here because the write happens while the process is shutting down.
     */
    fun save(geometry: WindowGeometry): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        temporary.writeText(json.encodeToString(geometry))
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    }.getOrElse { false }
}

/**
 * Moves a saved window back onto a screen, keeping at least [margin] of it reachable.
 *
 * The case this exists for is a saved position that no longer exists: the second monitor was
 * unplugged, the display layout changed, or the resolution dropped. Windows would happily restore the
 * window off-screen, where the user can neither see it nor drag it back -- and because the geometry is
 * re-saved on every close, that state would persist forever. [screenBounds] is the union of every
 * monitor's bounds, not the primary monitor's, so a window that was legitimately on a second screen is
 * left exactly where it was.
 */
fun WindowGeometry.clampToScreen(screenBounds: Rectangle, margin: Int = MINIMUM_VISIBLE): WindowGeometry {
    if (screenBounds.width <= 0 || screenBounds.height <= 0) return this

    val minX = screenBounds.x - width + margin
    val maxX = screenBounds.x + screenBounds.width - margin
    val minY = screenBounds.y - height + margin
    val maxY = screenBounds.y + screenBounds.height - margin

    return copy(
        x = x.coerceIn(minX, maxX),
        y = y.coerceIn(minY, maxY),
    )
}

/** How much of the window has to stay on a screen for it to count as reachable. */
const val MINIMUM_VISIBLE: Int = 120
