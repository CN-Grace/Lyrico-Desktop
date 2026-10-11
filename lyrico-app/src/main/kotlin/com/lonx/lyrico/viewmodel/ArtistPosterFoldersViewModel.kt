package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.utils.coil.ArtistPosterMatcher
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 海报文件夹里的一个文件。
 *
 * Android 存的是 SAF 的 `content://` uri，桌面端就是普通的绝对路径，所以字段叫 `path`：这个模型
 * 会被交给 Coil 解码，两边的取图方式是文件 vs. 流，字段名不改就会有谎。
 */
data class ArtistPosterFile(val path: String, val name: String, val lastModified: Long)

data class ArtistPosterFolder(
    val path: String,
    /** `null` 表示这个文件夹现在读不到（被删掉、改名，或者换成了普通文件）。 */
    val posters: List<ArtistPosterFile>?,
    /** How many posters in this folder are named after an artist of the library. */
    val matchedPosterCount: Int = 0,
    val name: String = File(path).name.ifBlank { path }
)

data class ArtistPosterFoldersUiState(
    val folder: ArtistPosterFolder? = null,
    val isLoading: Boolean = true,
    val error: Boolean = false,
    val revision: Long = 0L
)

/**
 * 艺术家海报文件夹：读当前文件夹里的海报、按文件名匹配库里的艺术家，并负责增删与刷新。
 *
 * 移植掉的三样东西：
 * * **`Application`**：Android 要靠它拿 `contentResolver` 列举 SAF 目录，也要靠 `UriUtils` 把
 *   tree uri 换成人能读的路径。桌面端 `SettingsRepository` 里存的就是绝对路径，`File` 直接读，
 *   两者都不需要。
 * * **`Uri`**：同上，`addFolder` 现在收路径字符串。
 * * **`SafDocuments.children`**：换成 [readPosters]，语义对齐在它的 KDoc 里。
 *
 * 匹配与排序逻辑没动：认人是 `ArtistPosterMatcher`。`matchedPosterCount` 在这里就算好了，但
 * **两个平台都没有界面在读它**（Android 也没有），保留是为了模型可比；要删也是清理批次的事，
 * 这次不动声明。
 */
class ArtistPosterFoldersViewModel(
    private val settings: SettingsRepository,
    private val libraryIndexRepository: LibraryIndexRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ArtistPosterFoldersUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settings.artistPosterFolder,
                settings.artistPosterRevision,
                libraryIndexRepository.observeArtists()
            ) { folder, revision, artists -> Triple(folder, revision, artists) }
                .collectLatest { (folder, revision, artists) ->
                    _uiState.update { it.copy(isLoading = true) }
                    val artistNames = artists.map { it.name }
                    val entry = withContext(Dispatchers.IO) {
                        folder?.let { path ->
                            val posters = readPosters(path)
                            ArtistPosterFolder(
                                path = path,
                                posters = posters,
                                matchedPosterCount = countMatchedPosters(posters, artistNames)
                            )
                        }
                    }
                    _uiState.update { it.copy(folder = entry, isLoading = false, revision = revision) }
                }
        }
    }

    /**
     * Posters are matched by file name, so a folder full of posters that matches no artist of the
     * library means the names do not line up - surface that instead of silently showing nothing.
     */
    private fun countMatchedPosters(posters: List<ArtistPosterFile>?, artistNames: List<String>): Int =
        ArtistPosterMatcher.countMatchedFiles(posters.orEmpty().map { it.name }, artistNames)

    /**
     * `null` when the folder cannot be listed any more (deleted, renamed, or replaced by a plain
     * file).
     *
     * Android got that `null` from `SafDocuments.children`, which returned it once the persisted tree
     * permission stopped resolving. `File.listFiles()` returns `null` for exactly the same reason
     * (the path is gone, or no longer a directory), so the flag keeps its meaning and the screen's
     * "folder unavailable" branch is reached by the same condition.
     */
    private fun readPosters(path: String): List<ArtistPosterFile>? =
        File(path).listFiles()
            ?.filterNot { it.isDirectory }
            ?.filter { ArtistPosterMatcher.isPosterFile(it.name) }
            ?.map { ArtistPosterFile(path = it.absolutePath, name = it.name, lastModified = it.lastModified()) }
            ?.sortedBy { it.name.lowercase() }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                _uiState.update { it.copy(error = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(error = true) }
            }
        }
    }

    fun addFolder(path: String) = update {
        settings.setArtistPosterFolder(path)
        settings.refreshArtistPosters()
    }

    fun removeFolder() = update {
        // Android 提醒过：授权是和音乐库共用的，这里只注销注册表里的这一条，不动磁盘上的文件。
        settings.clearArtistPosterFolder()
    }

    fun refresh() = update { settings.refreshArtistPosters() }
}
