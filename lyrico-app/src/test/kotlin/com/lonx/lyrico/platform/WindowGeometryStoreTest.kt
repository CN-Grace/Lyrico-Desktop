package com.lonx.lyrico.platform

import java.awt.Rectangle
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Window geometry across restarts, on the real filesystem.
 *
 * The file this exercises is written while the process is shutting down and read while it is starting,
 * which are exactly the two moments where an exception would be worst: a failed read must not stop the
 * app from opening, and a failed write must not leave a half-written file that stops the *next* run.
 * So the tests write real files, including deliberately broken ones.
 */
class WindowGeometryStoreTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-window-geometry").toFile()

    @AfterTest
    fun cleanUp() {
        workingDir.deleteRecursively()
    }

    private fun store(name: String = "window.json") = WindowGeometryStore(File(workingDir, name))

    private val geometry = WindowGeometry(x = 120, y = 80, width = 1400, height = 900)

    @Test
    fun `a saved geometry is read back unchanged`() {
        val store = store()

        assertTrue(store.save(geometry))

        assertEquals(geometry, store.load())
    }

    @Test
    fun `a missing file restores nothing`() {
        assertNull(store().load())
    }

    @Test
    fun `a truncated file restores nothing instead of throwing`() {
        val store = store()
        File(workingDir, "window.json").writeText("""{"x": 10, "y": 20, "wid""")

        assertNull(store.load())
    }

    @Test
    fun `a file with a field this build does not know is still read`() {
        val store = store()
        File(workingDir, "window.json").writeText(
            """{"x": 10, "y": 20, "width": 800, "height": 600, "maximized": true}""",
        )

        assertEquals(WindowGeometry(10, 20, 800, 600), store.load())
    }

    @Test
    fun `a size that could never be shown is rejected`() {
        val store = store()
        File(workingDir, "window.json").writeText("""{"x": 0, "y": 0, "width": 0, "height": 600}""")

        assertNull(store.load())
    }

    @Test
    fun `saving creates the folder it writes into`() {
        val nested = File(workingDir, "data")

        assertTrue(WindowGeometryStore(File(nested, "window.json")).save(geometry))
        assertTrue(File(nested, "window.json").isFile)
    }

    @Test
    fun `saving over an existing file replaces the previous geometry`() {
        val store = store()
        store.save(geometry)

        val resized = geometry.copy(width = 1024, height = 768)
        assertTrue(store.save(resized))

        assertEquals(resized, store.load())
    }

    @Test
    fun `saving leaves no temporary files behind`() {
        store().save(geometry)

        assertEquals(listOf("window.json"), workingDir.list()!!.sorted())
    }

    @Test
    fun `a geometry that is still on a screen is left where it was`() {
        val screen = Rectangle(0, 0, 1920, 1080)

        assertEquals(geometry, geometry.clampToScreen(screen))
    }

    @Test
    fun `a window pushed past the right edge is pulled back with a part still visible`() {
        val screen = Rectangle(0, 0, 1920, 1080)
        val offScreen = geometry.copy(x = 4000, y = 3000)

        val clamped = offScreen.clampToScreen(screen)

        assertEquals(1920 - MINIMUM_VISIBLE, clamped.x)
        assertEquals(1080 - MINIMUM_VISIBLE, clamped.y)
        // The size is the user's, not the screen's: only the position is corrected.
        assertEquals(offScreen.width, clamped.width)
        assertEquals(offScreen.height, clamped.height)
    }

    @Test
    fun `a window dragged past the top left is pulled back too`() {
        val screen = Rectangle(0, 0, 1920, 1080)
        val offScreen = geometry.copy(x = -3000, y = -2000)

        val clamped = offScreen.clampToScreen(screen)

        assertEquals(MINIMUM_VISIBLE - offScreen.width, clamped.x)
        assertEquals(MINIMUM_VISIBLE - offScreen.height, clamped.y)
    }

    @Test
    fun `a window on a monitor left of the primary one keeps its position`() {
        // The union of two monitors: the second one sits at a negative x.
        val screens = Rectangle(-1920, 0, 3840, 1080)
        val onSecondMonitor = geometry.copy(x = -1800, y = 100)

        assertEquals(onSecondMonitor, onSecondMonitor.clampToScreen(screens))
    }

    @Test
    fun `a window on a monitor whose resolution dropped is pulled back on screen`() {
        // Saved at 2560x1440 on a monitor that is now 1920x1080.
        val smallerScreen = Rectangle(0, 0, 1920, 1080)
        val saved = WindowGeometry(x = 2100, y = 1200, width = 900, height = 700)

        val clamped = saved.clampToScreen(smallerScreen)

        assertEquals(1920 - MINIMUM_VISIBLE, clamped.x)
        assertEquals(1080 - MINIMUM_VISIBLE, clamped.y)
    }

    @Test
    fun `an empty screen bounds leaves the geometry alone`() {
        val nothing = Rectangle(0, 0, 0, 0)

        assertEquals(geometry, geometry.clampToScreen(nothing))
    }

    @Test
    fun `clamping twice changes nothing the second time`() {
        val screen = Rectangle(0, 0, 1920, 1080)
        val once = geometry.copy(x = 5000, y = 5000).clampToScreen(screen)

        assertEquals(once, once.clampToScreen(screen))
    }

    @Test
    fun `the data folder keeps the window file beside the database`() {
        val directories = AppDirectories(root = workingDir, isPortable = true)

        assertTrue(directories.windowStateFile.parentFile == workingDir)
        assertTrue(directories.instanceLockFile.parentFile == workingDir)
        assertFalse(directories.windowStateFile.isDirectory)
    }
}
