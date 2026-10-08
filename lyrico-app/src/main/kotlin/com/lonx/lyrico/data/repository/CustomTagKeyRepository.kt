package com.lonx.lyrico.data.repository

import com.lonx.audiotag.model.CustomTagField
import com.lonx.lyrico.data.editfield.CustomTagKey
import com.lonx.lyrico.data.model.dao.CustomTagKeyCount
import com.lonx.lyrico.data.model.dao.SongCustomTagKeyDao
import kotlinx.coroutines.flow.Flow

class CustomTagKeyRepository(
    private val dao: SongCustomTagKeyDao,
) {

    fun observeKeyCounts(): Flow<List<CustomTagKeyCount>> {
        return dao.observeCustomTagKeyCounts()
    }

    suspend fun replaceForSong(
        songUri: String,
        customFields: List<CustomTagField>,
    ) {
        dao.replaceForSong(
            songUri = songUri,
            keys = customFields.mapNotNull { normalizeCustomTagKey(it.key) },
        )
    }

    suspend fun removeSongs(songUris: List<String>) {
        if (songUris.isEmpty()) return
        dao.deleteForSongs(songUris)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    suspend fun getSongUrisByKey(key: String): List<String> {
        return dao.getSongUrisByKey(normalizeCustomTagKey(key) ?: return emptyList())
    }

    private fun normalizeCustomTagKey(input: String): String? = CustomTagKey.normalize(input)
}
