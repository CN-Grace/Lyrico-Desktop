package com.lonx.lyrico.viewmodel

/**
 * Sort field for the song list, ported from the Android app.
 *
 * The Android original carried the label as a `@StringRes` id. Desktop resources are Compose
 * Multiplatform resources (`src/main/composeResources`, see PLAN.md §3), which are not wired up yet,
 * so the label is kept as the Android resource *name* — the exact key under which the same text
 * lives in `strings.xml`, with no text invented or lost. It becomes a `StringResource` when the
 * resource migration happens.
 */
enum class SortBy(
    val labelKey: String,
    val supportsIndex: Boolean
) {
    TITLE("label_title", true),
    ARTISTS("label_artists", true),
    ALBUM("label_album", true),
    DATE_MODIFIED("label_date_modified", false),
    DATE_ADDED("label_date_added", false),
    FILE_SIZE("label_file_size", false),
    DURATION("label_duration", false),
    EXTENSION("label_extension", false),
}

enum class SortOrder {
    ASC,
    DESC
}

data class SortInfo(
    val sortBy: SortBy = SortBy.TITLE,
    val order: SortOrder = SortOrder.ASC
)
