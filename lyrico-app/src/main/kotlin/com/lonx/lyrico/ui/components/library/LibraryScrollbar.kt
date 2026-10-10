package com.lonx.lyrico.ui.components.library

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Width of the thumb. The Android scrollbar had no counterpart knob; this is the desktop feel. */
private val ScrollbarThumbWidth = 6.dp

/** The thumb never shrinks below this, so a list of thousands of rows still shows a grabbable handle. */
private val ScrollbarMinimalThumbHeight = 32.dp

/**
 * The desktop replacement for `my.nanihadesuka.compose.InternalLazyColumnScrollbar`.
 *
 * That library has no JVM artifact at all (verified by resolving the dependency tree -- see
 * `PLAN.md`), so the library's list scrollbar is rebuilt on the scrollbar Compose Desktop ships:
 * `androidx.compose.foundation.VerticalScrollbar` driven by
 * [androidx.compose.foundation.rememberScrollbarAdapter], which reads a `LazyListState` directly.
 *
 * What carries over from the Android configuration, and what does not:
 *
 * - `alwaysShowScrollbar = true` becomes `hoverDurationMillis = 0`: the thumb stays visible instead
 *   of fading out when the pointer leaves.
 * - `thumbUnselectedColor` / `thumbSelectedColor` both become
 *   [MiuixTheme.colorScheme.onSurfaceVariantActions], the same colour Android used for both.
 * - `selectionMode = ScrollbarSelectionMode.Full` **is dropped**. It was an Android-only gesture
 *   that dragged a range selection into `SharedSelectionManager`; Compose Desktop's scrollbar only
 *   scrolls. Range selection is still reachable by swiping a row
 *   (`SongSelectionViewModel.swipeSelect`), which is the same entry point Android offered.
 *
 * The caller owns the placement: it aligns this to the list's trailing edge and insets it with
 * `Modifier.libraryScrollbarOverlay` so the thumb stops at the top and bottom bars.
 *
 * The [LazyGridState] overload below exists for the albums grid. Its adapter is a different type --
 * `androidx.compose.foundation.v2.ScrollbarAdapter`, because a grid position is not a line count, so
 * the size arithmetic cannot be shared with the list adapter. The Kotlin source name of the function
 * that builds it is `rememberScrollbarAdapter` too: the two families differ by parameter type
 * (`LazyGridState` only exists on the grid side) and are separated in bytecode by a `@JvmName`
 * suffix, which is invisible from Kotlin and is why both calls below read the same. The Android
 * original used a separate component for the grid (`InternalLazyVerticalGridScrollbar`) for the same
 * underlying reason; here the two share this file and the thumb style, and differ only in the
 * adapter.
 */
@Composable
internal fun LibraryScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val style = libraryScrollbarStyle()
    // The scrollbar draws at the style's thickness wherever it is placed, so the strip reserved by
    // `libraryScrollbarOverlay` is filled with the thumb pushed to its trailing edge.
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd,
    ) {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier
                .fillMaxHeight()
                .width(ScrollbarThumbWidth),
            style = style,
        )
    }
}

/** The albums grid's scrollbar. Same thumb and colours as the list overload above. */
@Composable
internal fun LibraryScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val style = libraryScrollbarStyle()
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd,
    ) {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier
                .fillMaxHeight()
                .width(ScrollbarThumbWidth),
            style = style,
        )
    }
}

@Composable
private fun libraryScrollbarStyle() = ScrollbarStyle(
    minimalHeight = ScrollbarMinimalThumbHeight,
    thickness = ScrollbarThumbWidth,
    shape = RoundedCornerShape(percent = 50),
    hoverDurationMillis = 0,
    unhoverColor = MiuixTheme.colorScheme.onSurfaceVariantActions,
    hoverColor = MiuixTheme.colorScheme.onSurfaceVariantActions,
)
