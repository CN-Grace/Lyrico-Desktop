package com.lonx.lyrico.data.repository

import com.lonx.lyrico.platform.FileRevealer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The share action's new meaning: reveal the song in Explorer.
 *
 * The files here are **real** files in a real temporary directory, because the whole job of this
 * repository is to decide which of the caller's paths still exists, and a fake filesystem would be
 * asserting the fake. The shell hand-off is the one thing that is faked (`FileRevealer`), so the tests
 * never open a window.
 */
class FileRevealRepositoryTest {

    private val tempDirs = mutableListOf<File>()
    private val revealed = mutableListOf<File>()

    private val revealer = object : FileRevealer {
        override fun reveal(file: File) {
            revealed.add(file)
        }
    }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): Path = Files.createTempDirectory("lyrico-reveal-repo").also {
        tempDirs += it.toFile()
    }

    private fun existingFile(name: String): Path {
        val path = tempDir().resolve(name)
        Files.writeString(path, "lyrico")
        return path
    }

    @Test
    fun `an existing file is revealed`() {
        val path = existingFile("song.mp3")
        val repository = FileRevealRepositoryImpl(revealer)

        val result = repository.reveal(listOf(path))

        assertEquals(RevealResult.Revealed(path.toAbsolutePath().normalize()), result)
        assertEquals(path.toAbsolutePath().normalize().toFile(), revealed.single())
    }

    @Test
    fun `an empty request does nothing`() {
        val repository = FileRevealRepositoryImpl(revealer)

        val result = repository.reveal(emptyList())

        assertEquals(RevealResult.NothingToReveal, result)
        assertTrue(revealed.isEmpty(), "an empty selection must not touch the shell at all")
    }

    @Test
    fun `a deleted file is reported instead of revealing its folder`() {
        val path = existingFile("song.mp3")
        Files.delete(path)
        val repository = FileRevealRepositoryImpl(revealer)

        val result = repository.reveal(listOf(path))

        assertIs<RevealResult.FilesUnavailable>(result)
        assertTrue(revealed.isEmpty(), "revealing an empty folder would look like a successful share")
    }

    @Test
    fun `the first existing file wins and the rest are ignored`() {
        val missing = tempDir().resolve("gone.mp3")
        val present = existingFile("present.mp3")
        val later = existingFile("later.mp3")
        val repository = FileRevealRepositoryImpl(revealer)

        val result = repository.reveal(listOf(missing, present, later))

        assertEquals(RevealResult.Revealed(present.toAbsolutePath().normalize()), result)
        assertEquals(1, revealed.size, "Explorer's /select takes one path, so only one file is revealed")
    }

    @Test
    fun `a directory is not treated as a file`() {
        val directory = Files.createDirectory(tempDir().resolve("album"))
        val repository = FileRevealRepositoryImpl(revealer)

        val result = repository.reveal(listOf(directory))

        assertIs<RevealResult.FilesUnavailable>(
            result,
            "selecting a directory is a different action; this one is about the song file",
        )
    }

    @Test
    fun `a shell failure is reported with the song it was for`() {
        val path = existingFile("song.mp3")
        val repository = FileRevealRepositoryImpl(
            object : FileRevealer {
                override fun reveal(file: File): Unit = throw java.io.IOException("explorer.exe is gone")
            }
        )

        val result = repository.reveal(listOf(path))

        val failed = assertIs<RevealResult.Failed>(result)
        assertEquals(path.toAbsolutePath().normalize(), failed.path)
        assertIs<java.io.IOException>(failed.throwable)
    }

    @Test
    fun `an unnormalized path is normalized before it reaches the shell`() {
        val directory = tempDir()
        val path = Files.writeString(directory.resolve("song.mp3"), "lyrico")
        // `tempDir/sub/../song.mp3`: absolute but not normalized, which is exactly the shape a path
        // assembled from user input has. Explorer gets the clean form.
        val messy = directory.resolve("sub").resolve("..").resolve("song.mp3")
        val repository = FileRevealRepositoryImpl(revealer)

        val result = repository.reveal(listOf(messy))

        assertEquals(RevealResult.Revealed(path.toAbsolutePath().normalize()), result)
        assertEquals(
            path.toAbsolutePath().normalize().toString(),
            revealed.single().path,
            "the shell must not be handed a path containing '..'",
        )
    }
}
