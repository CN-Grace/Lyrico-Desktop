package com.lonx.lyrico.ui.components.crop

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two pixel operations the desktop cropper needs, asserted on *pixels* rather than sizes.
 *
 * Android implemented these with `Bitmap.createScaledBitmap` and `Bitmap.createBitmap`, and neither had
 * a test -- a crop that copies the wrong region still returns an image of the right dimensions, so a
 * size assertion alone would have passed on a broken port. These use a bitmap whose every pixel encodes
 * its own coordinates, which makes the region-self-evident: after `crop(1, 2, 2, 2)`, the pixel at (0, 0)
 * of the result must be the colour that only source pixel (1, 2) has.
 *
 * The scale case asserts the corners of a two-colour image instead, because bilinear sampling makes
 * interior values a blend and only the corners are stable under any reasonable implementation.
 */
class ImageBitmapOpsTest {

    @Test
    fun `scaling produces exactly the requested size`() {
        val scaled = coordinateBitmap(8, 4).scaleTo(width = 3, height = 5)

        assertEquals(3, scaled.width)
        assertEquals(5, scaled.height)
    }

    @Test
    fun `scaling keeps the corners on the same corners`() {
        val source = ImageBitmap(4, 4)
        Canvas(source).apply {
            drawRect(0f, 0f, 4f, 4f, Paint().apply { color = Color.Blue })
            drawRect(0f, 0f, 2f, 2f, Paint().apply { color = Color.Red })
        }

        val scaled = source.scaleTo(width = 2, height = 2)

        assertEquals(Color.Red.toArgb(), scaled.toPixelMap()[0, 0].toArgb())
        assertEquals(Color.Blue.toArgb(), scaled.toPixelMap()[1, 1].toArgb())
    }

    @Test
    fun `a crop copies the region it was asked for`() {
        val source = coordinateBitmap(width = 4, height = 4)

        val cropped = source.crop(x = 1, y = 2, width = 2, height = 2)

        assertEquals(2, cropped.width)
        assertEquals(2, cropped.height)
        val pixels = cropped.toPixelMap()
        for (x in 0 until 2) {
            for (y in 0 until 2) {
                // The source pixel that must have landed here: the crop's (0, 0) is source (1, 2).
                val expected = Color(red = (x + 1) * 60, green = (y + 2) * 60, blue = 0)
                assertEquals(
                    expected.toArgb(),
                    pixels[x, y].toArgb(),
                    "crop($x, $y) is not source (${x + 1}, ${y + 2})",
                )
            }
        }
    }

    @Test
    fun `a one-pixel crop is a copy of that pixel, not of the whole image`() {
        val cropped = coordinateBitmap(width = 4, height = 4).crop(x = 3, y = 1, width = 1, height = 1)

        assertEquals(1, cropped.width)
        assertEquals(1, cropped.height)
        assertEquals(
            Color(red = 3 * 60, green = 1 * 60, blue = 0).toArgb(),
            cropped.toPixelMap()[0, 0].toArgb(),
        )
    }

    /**
     * A bitmap whose pixel (x, y) is `Color(red = x * 60, green = y * 60, blue = 0)`.
     *
     * 60 rather than 1 so that the encoded coordinate is unambiguous in an 8-bit channel, and so that a
     * channel mix-up cannot accidentally match. Anti-aliasing is off: with integer rectangle edges no
     * pixel is partially covered, and the colours must come back exactly.
     */
    private fun coordinateBitmap(width: Int, height: Int): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        for (x in 0 until width) {
            for (y in 0 until height) {
                canvas.drawRect(
                    left = x.toFloat(),
                    top = y.toFloat(),
                    right = x + 1f,
                    bottom = y + 1f,
                    paint = Paint().apply {
                        isAntiAlias = false
                        color = Color(red = x * 60, green = y * 60, blue = 0)
                    },
                )
            }
        }
        return bitmap
    }
}
