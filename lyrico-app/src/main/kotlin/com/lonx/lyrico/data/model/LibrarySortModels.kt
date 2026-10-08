package com.lonx.lyrico.data.model

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.label_album
import com.lonx.lyrico.resources.label_album_artist
import com.lonx.lyrico.resources.label_album_count
import com.lonx.lyrico.resources.label_name
import com.lonx.lyrico.resources.label_song_count
import com.lonx.lyrico.resources.label_year
import com.lonx.lyrico.viewmodel.SortOrder
import org.jetbrains.compose.resources.StringResource

enum class ArtistSortBy(
    val labelRes: StringResource,
    val supportsIndex: Boolean
) {
    NAME(Res.string.label_name, true),
    SONG_COUNT(Res.string.label_song_count, false),
    ALBUM_COUNT(Res.string.label_album_count, false)
}

data class ArtistSortInfo(
    val sortBy: ArtistSortBy = ArtistSortBy.NAME,
    val order: SortOrder = SortOrder.ASC
)

enum class AlbumSortBy(
    val labelRes: StringResource,
    val supportsIndex: Boolean
) {
    NAME(Res.string.label_album, true),
    ALBUM_ARTIST(Res.string.label_album_artist, false),
    SONG_COUNT(Res.string.label_song_count, false),
    YEAR(Res.string.label_year, false)
}

data class AlbumSortInfo(
    val sortBy: AlbumSortBy = AlbumSortBy.NAME,
    val order: SortOrder = SortOrder.ASC
)
