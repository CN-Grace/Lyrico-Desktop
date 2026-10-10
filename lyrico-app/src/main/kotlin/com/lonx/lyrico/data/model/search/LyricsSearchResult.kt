package com.lonx.lyrico.data.model.search

import com.lonx.lyrico.data.model.lyrics.sanitizeStandardFields
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget

/**
 * One lyrics hit produced by a plugin source.
 *
 * Android's copy was `@Parcelize`d so it could travel through the `SavedStateHandle` of the search
 * screen's previous back-stack entry. That transport is still how the result reaches the metadata
 * editor here — see `ui/navigation/ResultBackNavigator.kt` — but desktop has no `Bundle` and no
 * process death to survive, so `Parcelable` and the `kotlinx.parcelize` plugin are gone. Measured
 * in `SavedStateHandleProbeTest`: the multiplatform `SavedStateHandle` stores and returns this class
 * as-is, the same instance, with its `Set`/`Map`/enum fields intact. The data shape is otherwise
 * unchanged.
 */
data class LyricsSearchResult(
    val title: String?,
    val artist: String?,
    val album: String?,
    val lyrics: String?,
    val date: String?,
    val trackerNumber: String?,
    val picUrl: String?,
    val pluginId: String = "",
    val pluginName: String = "",
    val applyTargets: Set<MetadataFieldTarget> = emptySet(),
    val fields: Map<String, String> = emptyMap()
) {
    fun normalizedFields(): Map<String, String> {
        return buildMap {
            putAll(fields.sanitizeStandardFields())

            title?.takeIf { it.isNotBlank() }?.let { putIfAbsent("title", it) }
            artist?.takeIf { it.isNotBlank() }?.let { putIfAbsent("artist", it) }
            album?.takeIf { it.isNotBlank() }?.let { putIfAbsent("album", it) }
            date?.takeIf { it.isNotBlank() }?.let { putIfAbsent("date", it) }
            trackerNumber?.takeIf { it.isNotBlank() }?.let { putIfAbsent("track_number", it) }
            picUrl?.takeIf { it.isNotBlank() }?.let { putIfAbsent("cover_url", it) }
        }
    }
}
