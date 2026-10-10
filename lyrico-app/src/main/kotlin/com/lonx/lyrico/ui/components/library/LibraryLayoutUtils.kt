package com.lonx.lyrico.ui.components.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.ui.components.LocalScaffoldIncludesStartPadding

internal val FloatingNavigationBarHeight = 64.dp
internal val FloatingNavigationBarBottomMargin = 16.dp
internal val ContentBreathingRoom = 12.dp

/**
 * Width of the strip [libraryScrollbarOverlay] reserves along the list's trailing edge.
 *
 * Android sized this for `my.nanihadesuka.compose.InternalLazyColumnScrollbar`, a
 * `LazyColumn`-specific scrollbar that is Android-only. The desktop uses
 * [com.lonx.lyrico.ui.components.library.LibraryScrollbar] (`androidx.compose.foundation.VerticalScrollbar`)
 * instead, which draws a thumb no wider than the style's thickness, so this width is now only the
 * width of the strip that thumb is aligned to rather than the width of the draggable thumb.
 */
internal val LibraryScrollbarTrackWidth = 22.dp

internal val LocalLibraryBottomContentPadding = staticCompositionLocalOf { ContentBreathingRoom }

internal fun floatingContentBottomPadding(systemBottom: Dp, hasFloatingBar: Boolean): Dp =
    systemBottom + ContentBreathingRoom + if (hasFloatingBar) {
        FloatingNavigationBarHeight + FloatingNavigationBarBottomMargin
    } else {
        0.dp
    }

/**
 * The library's top bar / bottom bar are translucent layers drawn over the list, and the list itself
 * fills the screen. The scrollbar and the alphabet index have to be inset separately or the bars sit
 * on top of them.
 */
@Composable
internal fun Modifier.libraryOverlayInsets(
    paddingValues: PaddingValues,
    extraTop: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    return padding(
        start = if (LocalScaffoldIncludesStartPadding.current) {
            paddingValues.calculateStartPadding(layoutDirection)
        } else {
            0.dp
        },
        top = paddingValues.calculateTopPadding() + extraTop,
        end = paddingValues.calculateEndPadding(layoutDirection),
        bottom = LocalLibraryBottomContentPadding.current + extraBottom,
    )
}

@Composable
internal fun Modifier.libraryScrollbarOverlay(
    paddingValues: PaddingValues,
    extraTop: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    return fillMaxHeight()
        .padding(
            top = paddingValues.calculateTopPadding() + extraTop,
            end = paddingValues.calculateEndPadding(layoutDirection),
            bottom = LocalLibraryBottomContentPadding.current + extraBottom,
        )
        .width(LibraryScrollbarTrackWidth)
}
