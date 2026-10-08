package com.lonx.lyrico.data.model.search

import com.lonx.lyrico.data.model.entity.AlbumEntity
import com.lonx.lyrico.data.model.entity.ArtistEntity
import com.lonx.lyrico.data.model.entity.SongEntity

data class LocalSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val lyricSearchEnabled: Boolean = false,
    val songs: List<SongEntity> = emptyList(),
    val albums: List<AlbumEntity> = emptyList(),
    val artists: List<ArtistEntity> = emptyList(),
    val lyricMatches: List<LocalLyricSearchResult> = emptyList()
)

data class LocalLyricSearchResult(
    val song: SongEntity,
    val lyricLine: String
)
