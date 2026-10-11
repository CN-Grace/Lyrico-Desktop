package com.lonx.lyrico.ui.components.cover

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.lonx.lyrico.ui.components.CoverRequest
import com.lonx.lyrico.utils.logging.PlatformLog
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two non-drawing halves of the cover seam: turning a source into pixels, and measuring one.
 *
 * Both replace something that needed a `Context` on Android -- `getBitmap(context, data)` and
 * `BitmapFactory` plus `contentResolver.openInputStream` -- so the point of these assertions is that the
 * desktop versions behave the same on the same inputs *without* one. The `CoverRequest` cases are the
 * interesting ones: the UI holds no bytes for a poster that lives in a folder, so the size is measured
 * from the file the cover fetcher itself would resolve, and the test proves the two agree by writing one
 * real poster file and reading its real dimensions back.
 */
class CoverSourceImagesTest {

    private lateinit var workDir: Path

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("lyrico-cover-source-")
    }

    @AfterTest
    fun tearDown() {
        // The sink is a JVM-wide global; a capture left installed would swallow every later test's log.
        PlatformLog.resetSink()
        workDir.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------ decode to pixels

    @Test
    fun `bytes decode to a bitmap of the encoded size`() {
        val bitmap = decodeCoverBitmap(imageBytes(37, 23))

        assertEquals(37, bitmap?.width)
        assertEquals(23, bitmap?.height)
    }

    @Test
    fun `a file path decodes`() {
        val file = workDir.resolve("cover.png").toFile()
        file.writeBytes(imageBytes(37, 23))

        assertEquals(37, decodeCoverBitmap(file.absolutePath)?.width)
    }

    @Test
    fun `an image bitmap is passed through instead of being re-decoded`() {
        val source: ImageBitmap = ImageIO.read(ByteArrayInputStream(imageBytes(4, 4))).toComposeImageBitmap()

        assertSame(source, decodeCoverBitmap(source))
    }

    @Test
    fun `bytes that are not an image decode to nothing`() {
        assertNull(decodeCoverBitmap("not an image".toByteArray()))
    }

    @Test
    fun `a source this platform cannot classify decodes to nothing`() {
        assertNull(decodeCoverBitmap(42))
        assertNull(decodeCoverBitmap(null))
    }

    @Test
    fun `a source that cannot be read is logged and yields nothing`() {
        val logged = mutableListOf<String>()
        PlatformLog.sink = { _, tag, message, _ -> logged.add("$tag: $message") }

        val bitmap = decodeCoverBitmap(workDir.resolve("missing.png").toString())

        assertNull(bitmap, "a missing cover file must not produce an image")
        assertTrue(
            logged.any { it.contains("无法解码封面来源") },
            "Android printed the stack trace here; the port has to leave a trace in the app log: $logged",
        )
    }

    // ------------------------------------------------------------------ measuring

    @Test
    fun `the size of a byte array is read from its header`() = runBlocking {
        assertEquals(37 to 23, readCoverSourceSize(imageBytes(37, 23)))
    }

    @Test
    fun `the size of a file is read from its header`() = runBlocking {
        val file = workDir.resolve("cover.png").toFile()
        file.writeBytes(imageBytes(53, 31))

        assertEquals(53 to 31, readCoverSourceSize(file.absolutePath))
    }

    @Test
    fun `the size of an image bitmap is its own size`() = runBlocking {
        val source: ImageBitmap = ImageIO.read(ByteArrayInputStream(imageBytes(11, 29))).toComposeImageBitmap()

        assertEquals(11 to 29, readCoverSourceSize(source))
    }

    @Test
    fun `a cover request is measured from the poster file the fetcher would read`() = runBlocking {
        val posterDir = workDir.resolve("posters").also { Files.createDirectories(it) }
        Files.write(posterDir.resolve("Some Artist.png"), imageBytes(41, 17))

        val size = readCoverSourceSize(
            CoverRequest(
                uri = workDir.resolve("song.flac").toString(),
                lastUpdate = 0L,
                artistName = "Some Artist",
                artistPosterFolders = listOf(posterDir.toString()),
            )
        )

        assertEquals(41 to 17, size, "the editor must measure the poster it is about to display")
    }

    @Test
    fun `a cover request with no poster folder has no size`() = runBlocking {
        val size = readCoverSourceSize(
            CoverRequest(uri = workDir.resolve("song.flac").toString(), lastUpdate = 0L, artistName = "Nobody")
        )

        assertNull(size)
    }

    @Test
    fun `an unmeasurable source has no size`() = runBlocking {
        assertNull(readCoverSourceSize(42))
        assertNull(readCoverSourceSize(null))
    }

    private fun imageBytes(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { out ->
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out)
    }.toByteArray()
}
