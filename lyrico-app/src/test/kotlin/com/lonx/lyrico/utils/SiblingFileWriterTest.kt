package com.lonx.lyrico.utils

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two behaviours the SAF version's callers depend on, now that the walk is a `File`:
 * re-exporting replaces the previous bytes, and a path with no folder is refused instead of
 * written somewhere else.
 */
class SiblingFileWriterTest {

    private val workingDir: Path = Files.createTempDirectory("lyrico-siblingwriter-test")

    @AfterTest
    fun tearDown() {
        workingDir.toFile().deleteRecursively()
    }

    private fun audioFile(name: String = "Track.flac"): File =
        Files.createFile(workingDir.resolve(name)).toFile()

    @Test
    fun `writes bytes next to the audio file`() {
        val audio = audioFile()
        val lyrics = "作词：某人\n作曲：另一个人".toByteArray(Charsets.UTF_8)

        val written = SiblingFileWriter.write(audio, "Track.lrc", lyrics)

        assertEquals(workingDir.resolve("Track.lrc").toFile(), written, "the target is the audio file's folder")
        assertContentEquals(lyrics, written.readBytes(), "bytes are written verbatim, UTF-8 included")
        assertEquals(0L, audio.length(), "the audio file itself is not touched")
    }

    @Test
    fun `overwrites the existing sibling instead of appending`() {
        val audio = audioFile()

        SiblingFileWriter.write(audio, "cover.jpg", ByteArray(5))
        SiblingFileWriter.write(audio, "cover.jpg", ByteArray(2))

        // Android opened the document stream in "wt" mode for exactly this reason: exporting a new
        // cover must not leave the tail of the old one behind.
        assertEquals(2L, workingDir.resolve("cover.jpg").toFile().length())
    }

    @Test
    fun `closes the stream it hands to the caller`() {
        val audio = audioFile()
        var handedOut: java.io.OutputStream? = null

        SiblingFileWriter.write(audio, "cover.jpg") { outputStream ->
            handedOut = outputStream
            outputStream.write(byteArrayOf(1, 2, 3))
        }

        assertContentEquals(byteArrayOf(1, 2, 3), workingDir.resolve("cover.jpg").toFile().readBytes())
        val closed = assertFailsWith<IOException> { handedOut!!.write(4) }
        assertTrue(closed.message?.isNotBlank() == true, "writing to a closed stream must fail loudly")
    }

    @Test
    fun `refuses a path with no folder`() {
        // Android threw `IllegalStateException("Source folder unavailable")` when the document's
        // parent could not be resolved. A drive root has no parent folder, so it fails the same way
        // rather than writing the file into the root.
        val root = workingDir.root.toFile()

        val failure = assertFailsWith<IllegalStateException> {
            SiblingFileWriter.write(root, "cover.jpg", ByteArray(1))
        }

        assertEquals("Source folder unavailable", failure.message)
    }
}
