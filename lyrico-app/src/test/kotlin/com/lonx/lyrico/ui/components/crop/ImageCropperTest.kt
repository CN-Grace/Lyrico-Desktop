package com.lonx.lyrico.ui.components.crop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.ui.theme.LyricoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The cropper's geometry and coordinate mapping, which is all of the cropper that is not drawing.
 *
 * Everything here is a pure function of rectangles (`Offset`/`Rect` in, `Rect` or `DrawHandle` out), so
 * it runs without a window and without a device. Two of these cases are the ones a port is most likely
 * to get wrong and least likely to notice:
 *
 * * [cropActualBitmap] maps *view* coordinates back to *original* pixels through two nested scales
 *   (view -> displayed bitmap -> original bitmap). An inverted or single scale still produces an image
 *   of plausible dimensions, so the test asserts which source pixel ends up at the crop's corner.
 * * the drag maths has a minimum size, bounds clamping and a ratio lock that interact; the cases below
 *   pin down each of them separately, including that a drag past the edge stops rather than inverts.
 *
 * The Android version of this file's subject (`Bitmap`-based `getHitHandle`/`calculateNewRect`) was
 * untested; the arithmetic is byte-for-byte the same code, so these tests are new coverage of behaviour
 * that was previously only exercised by hand.
 */
@OptIn(ExperimentalTestApi::class)
class ImageCropperTest {

    private val bounds = Rect(left = 0f, top = 0f, right = 1000f, bottom = 1000f)
    private val rect = Rect(left = 100f, top = 100f, right = 300f, bottom = 300f)
    private val tolerance = 10f
    private val minSize = 60f

    // ------------------------------------------------------------------ hit testing

    @Test
    fun `each corner and edge of the crop rectangle is its own handle`() {
        assertEquals(DragHandle.TOP_LEFT, getHitHandle(Offset(100f, 100f), rect, tolerance))
        assertEquals(DragHandle.TOP_RIGHT, getHitHandle(Offset(300f, 100f), rect, tolerance))
        assertEquals(DragHandle.BOTTOM_LEFT, getHitHandle(Offset(100f, 300f), rect, tolerance))
        assertEquals(DragHandle.BOTTOM_RIGHT, getHitHandle(Offset(300f, 300f), rect, tolerance))
        assertEquals(DragHandle.LEFT, getHitHandle(Offset(100f, 200f), rect, tolerance))
        assertEquals(DragHandle.RIGHT, getHitHandle(Offset(300f, 200f), rect, tolerance))
        assertEquals(DragHandle.TOP, getHitHandle(Offset(200f, 100f), rect, tolerance))
        assertEquals(DragHandle.BOTTOM, getHitHandle(Offset(200f, 300f), rect, tolerance))
        assertEquals(DragHandle.CENTER, getHitHandle(Offset(200f, 200f), rect, tolerance))
    }

    @Test
    fun `a touch outside the rectangle that is not near it is no handle`() {
        assertEquals(DragHandle.NONE, getHitHandle(Offset(0f, 0f), rect, tolerance))
        assertEquals(DragHandle.NONE, getHitHandle(Offset(500f, 500f), rect, tolerance))
    }

    @Test
    fun `a corner can be grabbed from just outside it`() {
        // The tolerance exists so a 4 px corner bracket can be caught by a finger; grabbing from
        // slightly outside the rectangle is the whole point of it, not an accident.
        assertEquals(DragHandle.TOP_LEFT, getHitHandle(Offset(95f, 95f), rect, tolerance))
        assertEquals(DragHandle.BOTTOM_RIGHT, getHitHandle(Offset(305f, 305f), rect, tolerance))
    }

    // ------------------------------------------------------------------ dragging

    @Test
    fun `no handle means no movement`() {
        val dragged = calculateNewRect(
            handle = DragHandle.NONE,
            oldRect = rect,
            dragAmount = Offset(50f, 50f),
            bounds = bounds,
            minSize = minSize,
            aspectRatio = null,
        )

        assertSame(rect, dragged, "a NONE drag must return the rectangle it was given")
    }

    @Test
    fun `dragging the middle moves the rectangle without resizing it`() {
        val dragged = calculateNewRect(
            handle = DragHandle.CENTER,
            oldRect = rect,
            dragAmount = Offset(50f, -30f),
            bounds = bounds,
            minSize = minSize,
            aspectRatio = null,
        )

        assertEquals(Rect(150f, 70f, 350f, 270f), dragged)
    }

    @Test
    fun `dragging the middle stops at the edge instead of leaving it`() {
        val farLeft = calculateNewRect(
            handle = DragHandle.CENTER,
            oldRect = rect,
            dragAmount = Offset(-5_000f, 0f),
            bounds = bounds,
            minSize = minSize,
            aspectRatio = null,
        )
        val farRight = calculateNewRect(
            handle = DragHandle.CENTER,
            oldRect = rect,
            dragAmount = Offset(5_000f, 0f),
            bounds = bounds,
            minSize = minSize,
            aspectRatio = null,
        )

        assertEquals(Rect(0f, 100f, 200f, 300f), farLeft)
        assertEquals(Rect(800f, 100f, 1_000f, 300f), farRight)
    }

    @Test
    fun `dragging a corner resizes only that corner`() {
        val dragged = calculateNewRect(
            handle = DragHandle.TOP_LEFT,
            oldRect = rect,
            dragAmount = Offset(20f, 30f),
            bounds = bounds,
            minSize = minSize,
            aspectRatio = null,
        )

        assertEquals(Rect(120f, 130f, 300f, 300f), dragged)
    }

    @Test
    fun `a corner dragged across its opposite collapses to the minimum size at the bounds corner`() {
        // Each axis is clamped left/top-first (design: a crop rectangle is defined by its top-left
        // plus its size), so a drag far past the opposite corner does not invert the rectangle: the
        // top-left lands on the image's edge and the far side is pulled to the minimum size.
        val dragged = calculateNewRect(
            handle = DragHandle.BOTTOM_RIGHT,
            oldRect = rect,
            dragAmount = Offset(-5_000f, -5_000f),
            bounds = bounds,
            minSize = minSize,
            aspectRatio = null,
        )

        assertEquals(Rect(0f, 0f, 60f, 60f), dragged, "the rectangle may shrink to the minimum, not past it")
    }

    @Test
    fun `a side cannot be dragged outside the image`() {
        val dragged = calculateNewRect(
            handle = DragHandle.LEFT,
            oldRect = rect,
            dragAmount = Offset(-5_000f, 0f),
            bounds = bounds,
            minSize = minSize,
            aspectRatio = null,
        )

        assertEquals(Rect(0f, 100f, 300f, 300f), dragged)
    }

    @Test
    fun `a locked ratio survives a side drag`() {
        val dragged = calculateNewRect(
            handle = DragHandle.RIGHT,
            oldRect = rect,
            dragAmount = Offset(50f, 0f),
            bounds = bounds,
            minSize = minSize,
            aspectRatio = 1f,
        )

        // 250 x 250, re-centred vertically on the old middle (200) rather than anchored to the top.
        assertEquals(Rect(100f, 75f, 350f, 325f), dragged)
        assertEquals(dragged.width, dragged.height, "the ratio was not kept")
    }

    @Test
    fun `a locked ratio is not allowed to grow past the image`() {
        val dragged = calculateNewRect(
            handle = DragHandle.BOTTOM,
            oldRect = rect,
            dragAmount = Offset(0f, 5_000f),
            bounds = Rect(0f, 0f, 1_000f, 400f),
            minSize = minSize,
            aspectRatio = 1f,
        )

        // The drag is bounded by the image's bottom edge first (400), and the ratio then takes the
        // resulting height as its basis: the rectangle is 300 tall because its top stayed at 100, and
        // the width follows the ratio rather than the drag. It did not jump to the image's top edge.
        assertEquals(Rect(50f, 100f, 350f, 400f), dragged)
        assertEquals(dragged.width, dragged.height, "the width must follow the ratio, not the drag")
    }

    // ------------------------------------------------------------------ view -> original pixels

    @Test
    fun `a crop maps view coordinates back to original pixels`() {
        // A 100x100 original shown at half size (50x50) inside a 200x200 view: 2 original pixels per
        // view unit. The crop covers the middle half of the view, so it must land on (25, 25)-(74, 74).
        val original = coordinateBitmap(size = 100)
        val display = ImageBitmap(50, 50)

        val cropped = cropActualBitmap(
            originalBitmap = original,
            displayBitmap = display,
            imageDisplayBounds = Rect(0f, 0f, 200f, 200f),
            cropRect = Rect(50f, 50f, 150f, 150f),
        )

        assertEquals(50, cropped.width)
        assertEquals(50, cropped.height)
        val pixels = cropped.toPixelMap()
        assertEquals(intColor(25, 25).toArgb(), pixels[0, 0].toArgb(), "the crop did not start at (25, 25)")
        assertEquals(intColor(74, 74).toArgb(), pixels[49, 49].toArgb(), "the crop did not end at (74, 74)")
    }

    @Test
    fun `an off-centre image is mapped from the image's own top-left corner`() {
        // The image occupies (100, 60)-(300, 260) of the view, so a crop whose left edge is at the
        // image's left edge must start at original pixel 0 rather than at the view's left edge.
        val original = coordinateBitmap(size = 100)

        val cropped = cropActualBitmap(
            originalBitmap = original,
            displayBitmap = ImageBitmap(50, 50),
            imageDisplayBounds = Rect(100f, 60f, 300f, 260f),
            cropRect = Rect(100f, 60f, 200f, 160f),
        )

        assertEquals(50, cropped.width)
        assertEquals(50, cropped.height)
        assertEquals(intColor(0, 0).toArgb(), cropped.toPixelMap()[0, 0].toArgb())
    }

    @Test
    fun `a crop that runs past the original is clamped, not refused`() {
        val original = coordinateBitmap(size = 100)

        val cropped = cropActualBitmap(
            originalBitmap = original,
            displayBitmap = ImageBitmap(50, 50),
            imageDisplayBounds = Rect(0f, 0f, 200f, 200f),
            // Starts at (180, 180) -- original pixel 90 -- and asks for 60 pixels, 10 more than are left.
            cropRect = Rect(180f, 180f, 300f, 300f),
        )

        assertEquals(10, cropped.width)
        assertEquals(10, cropped.height)
        assertEquals(intColor(90, 90).toArgb(), cropped.toPixelMap()[0, 0].toArgb())
    }

    // ------------------------------------------------------------------ state

    @Test
    fun `a small image is not rescaled when the cropper is remembered`() = runComposeUiTest {
        val original = coordinateBitmap(size = 100)
        var state: ImageCropperState? = null

        setContent {
            LyricoTheme {
                state = rememberImageCropperState(original)
                // Rendered without the ratio bar: the bar is a miuix spinner whose own behaviour is not
                // what this test is about, and the canvas path below it is what must survive headlessly.
                ImageCropper(state = state!!, showRatioBar = false)
            }
        }

        runOnIdle {
            val remembered = state!!
            assertSame(original, remembered.displayBitmap, "a 100x100 image must not be re-scaled")
            assertSame(original, remembered.originalBitmap)
        }
    }

    @Test
    fun `a huge image is scaled down before it is drawn, and still cropped from the original`() {
        // The threshold is a defaulted parameter so the boundary can be tested with a 40-pixel image
        // rather than a 40-megapixel one.
        val original = coordinateBitmap(size = 8)

        val scaled = downscaleBitmap(original, maxPixels = 16)

        assertEquals(4, scaled.width, "the downscale must fit the pixel budget")
        assertEquals(4, scaled.height)
        assertEquals(8, original.width, "the original must be left alone")
    }

    // ------------------------------------------------------------------ helpers

    /** `Color(red = x * 2, green = y * 2, blue = 0)` for pixel (x, y) -- see `ImageBitmapOpsTest`. */
    private fun intColor(x: Int, y: Int) = Color(red = x * 2, green = y * 2, blue = 0)

    private fun coordinateBitmap(size: Int): ImageBitmap {
        val bitmap = ImageBitmap(size, size)
        val canvas = Canvas(bitmap)
        for (x in 0 until size) {
            for (y in 0 until size) {
                canvas.drawRect(
                    left = x.toFloat(),
                    top = y.toFloat(),
                    right = x + 1f,
                    bottom = y + 1f,
                    paint = Paint().apply {
                        isAntiAlias = false
                        color = intColor(x, y)
                    },
                )
            }
        }
        return bitmap
    }
}
