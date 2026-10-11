package com.lonx.lyrico.ui.components.crop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/**
 * The two pixel operations the cropper needs, expressed on Compose's [ImageBitmap].
 *
 * Android did them with `Bitmap.createScaledBitmap` and `Bitmap.createBitmap`; both are Android-only
 * APIs, and the Android tree's `Bitmap` never crosses into the desktop interface layer -- the cover
 * pipeline hands the cropper an [ImageBitmap] and the view model encodes it with `ImageIO` only when
 * it is written to a file. Skia draws the copy, through the same
 * [androidx.compose.ui.graphics.Canvas] Compose itself uses, so no AWT round trip is involved and the
 * result is identical on a headless test JVM.
 *
 * They live in their own file rather than in `ImageCropper.kt` for one boring reason: that file
 * already imports `androidx.compose.foundation.Canvas` (the composable), and Kotlin does not allow
 * two imports with the same simple name.
 */

/**
 * A [width] x [height] copy of this bitmap, drawn with bilinear sampling -- the same filtering
 * Android's `Bitmap.createScaledBitmap(source, width, height, filter = true)` used.
 *
 * An exception for a non-positive size is left to [ImageBitmap]'s own factory: asking for a zero-pixel
 * image is a caller bug, not something to silently clamp here.
 */
internal fun ImageBitmap.scaleTo(width: Int, height: Int): ImageBitmap {
    val scaled = ImageBitmap(width, height)
    scaled.drawInto {
        drawImage(
            image = this@scaleTo,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, height),
            // Bilinear, the same filtering Android's filter = true asked for. Skia would pick the
            // same by default; saying it out loud is what keeps a later default from changing artwork.
            filterQuality = FilterQuality.Low
        )
    }
    return scaled
}

/**
 * The [width] x [height] region at ([x], [y]), copied -- Android's
 * `Bitmap.createBitmap(source, x, y, width, height)`.
 *
 * A copy rather than a view, deliberately: a shared region would keep the whole original alive and
 * would let a later write to the original show through the crop. Callers are responsible for keeping
 * the region inside the source, exactly as they were on Android.
 */
internal fun ImageBitmap.crop(x: Int, y: Int, width: Int, height: Int): ImageBitmap {
    val cropped = ImageBitmap(width, height)
    cropped.drawInto {
        drawImage(
            image = this@crop,
            srcOffset = IntOffset(x, y),
            srcSize = IntSize(width, height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, height)
        )
    }
    return cropped
}

/**
 * Runs [block]'s drawing commands into [this] bitmap, scaled 1:1.
 *
 * A [Canvas] alone only offers the three-argument `drawImage(image, topLeftOffset, paint)` -- the
 * source-rectangle and filtering overloads live on [DrawScope] -- so the copy goes through the same
 * [CanvasDrawScope] Compose itself uses to run a `Canvas` composable. The density and layout direction
 * are the defaults for a screen at scale 1: this copies pixels, and neither affects pixel coordinates.
 */
private inline fun ImageBitmap.drawInto(block: DrawScope.() -> Unit) {
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(this),
        size = Size(width.toFloat(), height.toFloat()),
        block = block
    )
}
