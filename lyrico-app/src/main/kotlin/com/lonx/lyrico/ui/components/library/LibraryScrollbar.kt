package com.lonx.lyrico.ui.components.library

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
 */
@Composable
internal fun LibraryScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val style = ScrollbarStyle(
        minimalHeight = ScrollbarMinimalThumbHeight,
        thickness = ScrollbarThumbWidth,
        shape = RoundedCornerShape(percent = 50),
        hoverDurationMillis = 0,
        unhoverColor = MiuixTheme.colorScheme.onSurfaceVariantActions,
        hoverColor = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
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
