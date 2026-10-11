package com.lonx.lyrico.utils

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Measuring an image the process already holds, the desktop replacement for
 * `BitmapFactory.Options(inJustDecodeBounds = true)`.
 *
 * The size is a *decoded header* claim, so the tests use two formats whose headers differ structurally
 * (PNG declares its size in a chunk, JPEG in a segment) and assert the exact numbers that were written.
 * A single format would not have caught a reader that happens to work for PNG only, and the editor shows
 * this number for both.
 *
 * The `null` cases are the behaviour the badge depends on: a cover that cannot be measured shows no
 * size instead of failing the page. Android's `decodeFile`/`decodeByteArray` returned `null` for the
 * same inputs and said nothing, and this keeps that contract.
 */
class LocalImageSizeTest {

    private lateinit var workDir: Path

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("lyrico-local-size-")
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    @Test
    fun `a png's size is read from its bytes`() {
        assertEquals(37 to 23, readLocalImageSize(imageBytes(37, 23, "png")))
    }

    @Test
    fun `a jpeg's size is read from its bytes`() {
        assertEquals(61 to 19, readLocalImageSize(imageBytes(61, 19, "jpg")))
    }

    @Test
    fun `a file on disk is measured the same way as its bytes`() {
        val file = workDir.resolve("cover.png").toFile()
        file.writeBytes(imageBytes(53, 31, "png"))

        assertEquals(53 to 31, readLocalImageSize(file))
    }

    @Test
    fun `bytes that are not an image have no size`() {
        assertNull(readLocalImageSize("not an image, just a 404 body".toByteArray()))
    }

    @Test
    fun `an empty byte array has no size`() {
        assertNull(readLocalImageSize(ByteArray(0)))
    }

    @Test
    fun `a file that does not exist has no size instead of throwing`() {
        assertNull(readLocalImageSize(workDir.resolve("missing.png").toFile()))
    }

    private fun imageBytes(width: Int, height: Int, format: String): ByteArray =
        ByteArrayOutputStream().also { out ->
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out)
        }.toByteArray()
}
