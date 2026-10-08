package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.editfield.CustomTagKey
import com.lonx.lyrico.data.editfield.EditFieldConfigRepository
import com.lonx.lyrico.data.editfield.EditFieldDefinition
import com.lonx.lyrico.data.editfield.EditFieldListItem
import com.lonx.lyrico.data.editfield.EditFieldRegistry
import com.lonx.lyrico.data.model.dao.CustomTagKeyCount
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.isTargetBlank
import com.lonx.lyrico.data.repository.CustomTagKeyRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CustomTagKeyError { EMPTY, INVALID, DUPLICATE }

data class EditFieldSettingsUiState(
    val items: List<EditFieldListItem> = emptyList(),
    val enabledCount: Int = 0,
    val componentOverrides: Map<String, Boolean> = emptyMap(),
    val availableKeys: List<String> = emptyList(),
    val inputError: CustomTagKeyError? = null,
    val selectedField: EditFieldDefinition? = null,
    val songsWithField: List<SongEntity> = emptyList(),
    val songsWithoutField: List<SongEntity> = emptyList(),
    val isLoadingSongs: Boolean = false,
    val songsWithFieldTruncated: Boolean = false,
    val songsWithoutFieldTruncated: Boolean = false,
    val songsLoadFailed: Boolean = false,
)

class EditFieldSettingsViewModel(
    private val configRepository: EditFieldConfigRepository,
    private val customTagKeyRepository: CustomTagKeyRepository,
    private val songLibraryRepository: SongLibraryRepository,
    private val audioTagRepository: AudioTagRepository,
) : ViewModel() {
    private data class SongListState(
        val field: EditFieldDefinition? = null,
        val withField: List<SongEntity> = emptyList(),
        val withoutField: List<SongEntity> = emptyList(),
        val loading: Boolean = false,
        val withTruncated: Boolean = false,
        val withoutTruncated: Boolean = false,
        val failed: Boolean = false,
    )

    private val inputError = MutableStateFlow<CustomTagKeyError?>(null)
    private val songList = MutableStateFlow(SongListState())
    private var songJob: Job? = null

    val uiState = combine(
        configRepository.configFlow, customTagKeyRepository.observeKeyCounts(), inputError, songList,
    ) { config, counts, error, songs ->
        val items = config.fieldItems()
        EditFieldSettingsUiState(
            items = items,
            enabledCount = config.allFields.count { config.isEffectivelyEnabled(it) },
            componentOverrides = config.componentOverrides,
            availableKeys = counts.filterNot { it.key in config.customTags }
                .sortedWith(compareByDescending<CustomTagKeyCount> { it.songCount }.thenBy { it.key })
                .map { it.key },
            inputError = error,
            selectedField = songs.field,
            songsWithField = songs.withField,
            songsWithoutField = songs.withoutField,
            isLoadingSongs = songs.loading,
            songsWithFieldTruncated = songs.withTruncated,
            songsWithoutFieldTruncated = songs.withoutTruncated,
            songsLoadFailed = songs.failed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditFieldSettingsUiState())

    fun setEnabled(code: String, enabled: Boolean) {
        viewModelScope.launch { configRepository.setEnabled(code, enabled) }
    }

    fun setComponentEnabled(key: String, enabled: Boolean) {
        viewModelScope.launch { configRepository.setComponentEnabled(key, enabled) }
    }

    fun setBlockOrder(keys: List<String>) {
        viewModelScope.launch { configRepository.setBlockOrder(keys) }
    }

    fun setComponentOrder(key: String, codes: List<String>) {
        viewModelScope.launch { configRepository.setComponentOrder(key, codes) }
    }

    fun addAvailableKey(key: String) {
        viewModelScope.launch { configRepository.addCustomTag(key) }
    }

    suspend fun addCustomTag(input: String): Boolean {
        val normalized = CustomTagKey.normalize(input)
        if (normalized == null) {
            inputError.value = if (input.isBlank()) CustomTagKeyError.EMPTY else CustomTagKeyError.INVALID
            return false
        }
        if (normalized in configRepository.configFlow.first().customTags) {
            inputError.value = CustomTagKeyError.DUPLICATE
            return false
        }
        inputError.value = null
        configRepository.addCustomTag(normalized)
        return true
    }

    fun removeCustomTag(key: String) {
        viewModelScope.launch { configRepository.removeCustomTag(key) }
    }

    fun resetAll() {
        viewModelScope.launch { configRepository.resetAll() }
    }

    fun clearInputError() { inputError.value = null }

    fun showFieldSongs(field: EditFieldDefinition, component: Boolean = false) {
        songJob?.cancel()
        songList.value = SongListState(field = field, loading = true)
        songJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val allSongs = songLibraryRepository.observeSongs(SortBy.TITLE, SortOrder.ASC).first()
                    val key = EditFieldRegistry.customTagKeyOf(field.code)
                    val taggedUris = key?.let { customTagKeyRepository.getSongUrisByKey(it).toSet() }
                    val withField = mutableListOf<SongEntity>()
                    val withoutField = mutableListOf<SongEntity>()
                    var withCount = 0
                    var withoutCount = 0
                    var failed = false
                    for (song in allSongs) {
                        ensureActive()
                        val hasValue = try {
                            when {
                                component -> EditFieldRegistry.fields
                                    .filter { it.kind == field.kind }
                                    .any { !song.isTargetBlank(requireNotNull(it.target)) }
                                taggedUris != null -> song.uri in taggedUris
                                field.code == "picture" -> audioTagRepository.read(song.uri).pictures.isNotEmpty()
                                field.code == "lyrics_offset" -> OFFSET_TAG.containsMatchIn(song.lyrics.orEmpty())
                                else -> !song.isTargetBlank(requireNotNull(field.target))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 无法读取的文件不归入“没有该字段”。
                            failed = true
                            continue
                        }
                        if (hasValue) {
                            withCount++
                            if (withField.size < MAX_SONGS) withField.add(song)
                        } else {
                            withoutCount++
                            if (withoutField.size < MAX_SONGS) withoutField.add(song)
                        }
                    }
                    SongListState(field = field, withField = withField, withoutField = withoutField,
                        withTruncated = withCount > MAX_SONGS, withoutTruncated = withoutCount > MAX_SONGS,
                        failed = failed)
                }
                songList.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                songList.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun clearFieldSongs() {
        songJob?.cancel()
        songList.value = SongListState()
    }

    private companion object {
        const val MAX_SONGS = 500
        val OFFSET_TAG = Regex("\\[offset:[+-]?\\d+\\]", RegexOption.IGNORE_CASE)
    }
}
