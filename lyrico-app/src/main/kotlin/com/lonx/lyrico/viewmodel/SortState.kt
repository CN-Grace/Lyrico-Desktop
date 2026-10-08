package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.label_album
import com.lonx.lyrico.resources.label_artists
import com.lonx.lyrico.resources.label_date_added
import com.lonx.lyrico.resources.label_date_modified
import com.lonx.lyrico.resources.label_duration
import com.lonx.lyrico.resources.label_extension
import com.lonx.lyrico.resources.label_file_size
import com.lonx.lyrico.resources.label_title
import org.jetbrains.compose.resources.StringResource

/**
 * Sort field for the song list, ported from the Android app.
 *
 * The Android original carried the label as a `@StringRes Int`; desktop resources are Compose
 * Multiplatform resources, so the label is the [StringResource] itself and call sites resolve it
 * with `stringResource(sortBy.labelRes)`. Every name here exists in `composeResources/values/strings.xml`.
 */
enum class SortBy(
    val labelRes: StringResource,
    val supportsIndex: Boolean
) {
    TITLE(Res.string.label_title, true),
    ARTISTS(Res.string.label_artists, true),
    ALBUM(Res.string.label_album, true),
    DATE_MODIFIED(Res.string.label_date_modified, false),
    DATE_ADDED(Res.string.label_date_added, false),
    FILE_SIZE(Res.string.label_file_size, false),
    DURATION(Res.string.label_duration, false),
    EXTENSION(Res.string.label_extension, false),
}

enum class SortOrder {
    ASC,
    DESC
}

data class SortInfo(
    val sortBy: SortBy = SortBy.TITLE,
    val order: SortOrder = SortOrder.ASC
)
