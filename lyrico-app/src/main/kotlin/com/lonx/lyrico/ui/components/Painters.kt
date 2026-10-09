package com.lonx.lyrico.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The painter helpers the ported screens share.
 *
 * These three classes were the platform-independent half of the Android `ui/components/PainterUtils.kt`
 * (the other half -- `getBitmap`/`saveBitmap`/`getSystemWallpaperColor` -- is `Context`, `BitmapFactory`,
 * `ImageDecoder` and dynamic colour, i.e. Android-only, and stays in the excluded tree until a screen
 * needs it). They live in their own file rather than being copied over `PainterUtils.kt` so the two
 * trees cannot disagree about what `PainterUtils.kt` contains: a same-named file in both trees is
 * exactly what `scripts/port-frontier.py` reports as a stale duplicate.
 */

/**
 * Creates a [Painter] that applies a tint colour to the source painter.
 * Used for theming icons that don't support direct tinting.
 */
@Composable
fun rememberTintedPainter(
    painter: Painter,
    tint: Color
): Painter = remember(tint, painter) {
    TintedPainter(painter, tint)
}

private class TintedPainter(
    private val painter: Painter,
    private val tint: Color
) : Painter() {
    override val intrinsicSize = painter.intrinsicSize

    override fun DrawScope.onDraw() {
        with(painter) {
            draw(size, colorFilter = ColorFilter.tint(tint))
        }
    }
}

class RoundedRectanglePainter(
    private val cornerRadius: Dp = 6.dp
) : Painter() {
    override val intrinsicSize = Size.Unspecified

    override fun DrawScope.onDraw() {
        drawRoundRect(
            color = Color.White,
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        )
    }
}
