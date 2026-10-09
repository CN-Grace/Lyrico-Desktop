package com.lonx.lyrico.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Explorer hand-off, checked without opening Explorer.
 *
 * The interesting part of this class is the argument it builds, because `explorer.exe` treats
 * `/select` and the path as two different requests: `/select` on its own opens *Documents*, and the
 * comma-joined `/select,C:\path` selects the file. A test that launched the real process would not
 * catch that — it would just open a window — so the launcher is injected and the exact argument list
 * is asserted instead.
 */
class ExplorerFileRevealerTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("lyrico-revealer").toFile().also {
        tempDirs += it
    }

    @Test
    fun `revealing a file passes one comma-joined select argument`() {
        val file = File(tempDir(), "song.mp3")
        file.writeText("x")
        val commands = mutableListOf<List<String>>()
        val revealer = ExplorerFileRevealer { commands.add(it) }

        revealer.reveal(file)

        assertEquals(1, commands.size)
        assertEquals(
            listOf("explorer.exe", "/select,${file.absolutePath}"),
            commands.single(),
            "explorer.exe needs /select and the path in ONE argument; two arguments open Documents instead",
        )
    }

    @Test
    fun `a relative path is resolved before it reaches the shell`() {
        val commands = mutableListOf<List<String>>()
        val revealer = ExplorerFileRevealer { commands.add(it) }

        revealer.reveal(File("relative/song.mp3"))

        val argument = commands.single()[1]
        assertTrue(
            argument.removePrefix("/select,").let { File(it).isAbsolute },
            "explorer.exe resolves relative paths against its own working directory, so the path must be absolute",
        )
        assertTrue(argument.endsWith("song.mp3"), "the path itself must survive: $argument")
    }

    @Test
    fun `a launcher that throws propagates so the caller can report it`() {
        val revealer = ExplorerFileRevealer { throw java.io.IOException("no shell") }

        val thrown = runCatching { revealer.reveal(File(tempDir(), "song.mp3")) }

        // The command is best-effort: the caller decides what to tell the user, and this class must not
        // swallow the failure into a silent no-op.
        assertTrue(thrown.isFailure, "swallowing the launch failure would make a broken shell look like success")
    }
}
