package com.lonx.lyrico.data.repository

import com.lonx.lyrico.platform.AwtDesktopOpener
import com.lonx.lyrico.platform.DesktopOpener
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The shell hand-off, with the shell faked.
 *
 * The real implementation calls `java.awt.Desktop.open`, and a test that reached it would launch a
 * media player on whatever machine runs the suite — so the seam is injected and the assertions are
 * about *what would have been handed over*. The file checks are real: the paths below are real
 * temp files, so "a missing file is never handed over" is a statement about the filesystem rather
 * than about a stub.
 */
class PlaybackRepositoryTest {

    private val opened = mutableListOf<File>()

    private fun repository(
        supported: Boolean = true,
        onOpen: ((File) -> Unit)? = null,
    ) = PlaybackRepositoryImpl(
        desktopOpener = object : DesktopOpener {
            override fun isSupported(): Boolean = supported
            override fun open(file: File) {
                opened += file
                onOpen?.invoke(file)
            }
        },
    )

    private fun fixture(): Path {
        val dir = Files.createTempDirectory("lyrico-playback")
        val target = dir.resolve("bladeenc.mp3")
        Files.copy(
            Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), "bladeenc.mp3"),
            target,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return target
    }

    @Test
    fun `a song is handed to the shell as a file`() {
        val song = fixture().toRealPath()

        val result = repository().open(song)

        assertEquals(PlaybackResult.Opened, result)
        assertEquals(listOf(song.toFile()), opened)
    }

    @Test
    fun `a file that is not there is never handed over`() {
        val missing = fixture().toRealPath().resolveSibling("gone.mp3")

        val result = repository().open(missing)

        assertEquals(PlaybackResult.FileUnavailable(missing), result)
        assertTrue(opened.isEmpty(), "the shell must not be asked to open something that is not there")
    }

    @Test
    fun `a directory is never handed over`() {
        val directory = fixture().toRealPath().parent

        val result = repository().open(directory)

        assertEquals(PlaybackResult.FileUnavailable(directory), result)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `the path handed over is absolute and has no dot segments`() {
        // `Desktop.open` throws IllegalArgumentException on a relative path, and a path spelled with
        // `..` is not what the file is called.
        val song = fixture().toRealPath()
        val spelled = song.parent.resolve("sub").resolve("..").resolve(song.fileName)

        val result = repository().open(spelled)

        assertEquals(PlaybackResult.Opened, result)
        assertEquals(listOf(song.toFile()), opened)
    }

    @Test
    fun `an environment that cannot open files says so instead of failing`() {
        val song = fixture().toRealPath()

        val result = repository(supported = false).open(song)

        assertEquals(PlaybackResult.Unsupported, result)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `an environment that cannot open files is reported before the file is even looked for`() {
        // The order matters for the message the UI shows: "系统不支持" is true of any path.
        val result = repository(supported = false).open(Path.of("""H:\nowhere\gone.mp3"""))

        assertEquals(PlaybackResult.Unsupported, result)
    }

    @Test
    fun `a shell that refuses the file is reported with the reason`() {
        val song = fixture().toRealPath()

        val result = repository(onOpen = { throw IOException("no program is associated with .mp3") }).open(song)

        val failed = assertIs<PlaybackResult.Failed>(result)
        assertEquals(song, failed.path)
        assertEquals("no program is associated with .mp3", failed.throwable.message)
    }

    @Test
    fun `a shell that throws something other than an io error is still reported, not propagated`() {
        val song = fixture().toRealPath()

        val result = repository(onOpen = { throw SecurityException("denied") }).open(song)

        assertIs<PlaybackResult.Failed>(result)
    }

    @Test
    fun `the real shell hand-off can be asked whether it is available`() {
        // A smoke test of the fallback path: constructing the real opener and asking it must not throw,
        // whether this JVM has a desktop session or not.
        val answer = runCatching { AwtDesktopOpener().isSupported() }

        assertTrue(answer.isSuccess, "isSupported() must answer, not throw: ${answer.exceptionOrNull()}")
    }
}
