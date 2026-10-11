package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.utils.LibraryScanManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FolderManagerUiState(
    val folders: List<FolderEntity> = emptyList(),
    val scanningFolderIds: Set<Long> = emptySet(),
    val queuedFolderIds: Set<Long> = emptySet(),
    val error: String? = null
)

/**
 * 文件夹管理页的视图模型。
 *
 * 与安卓版的差别都来自「SAF 退场」这一处：构造参数里不再有 `Application`（它只用来拿
 * `contentResolver`），`addFolder` 只收路径（桌面端没有要记录的 tree uri），删除文件夹时也不再
 * 释放持久化授权 —— 桌面端选文件夹走的是系统文件对话框，它不授予任何比进程活得更久的权限，
 * 所以安卓那句「释放失败就记一条 warning」在这里没有对应的失败可记，不是被吞掉了。
 *
 * `addedBySaf` 这个字段仍然有意义，但含义已经换过：桌面端它表示「用户显式加进来的根目录」
 * （见 [LibraryScanManager]），库根目录与扫描范围都按它筛选，所以这里照旧读、不重写。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderManagerViewModel(
    private val database: LyricoDatabase,
    private val libraryScanManager: LibraryScanManager,
    private val appScope: CoroutineScope,
    private val libraryIndexRepository: LibraryIndexRepository,
    private val songLibraryRepository: SongLibraryRepository
) : ViewModel() {

    private val folderDao = database.folderDao()

    private val _sortInfo = MutableStateFlow(SortInfo())
    val sortInfo: StateFlow<SortInfo> = _sortInfo.asStateFlow()

    private val _currentFolderId = MutableStateFlow<Long?>(null)

    val currentFolderSongs: StateFlow<List<SongEntity>> =
        combine(
            _currentFolderId,
            _sortInfo
        ) { folderId, sortInfo ->
            folderId to sortInfo
        }.flatMapLatest { (folderId, sortInfo) ->
            if (folderId == null) {
                flowOf(emptyList())
            } else {
                songLibraryRepository.observeSongs(sortInfo.sortBy, sortInfo.order, folderId)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    val uiState: StateFlow<FolderManagerUiState> =
        combine(
            folderDao.getAllFolders(),
            libraryScanManager.state
        ) { folders, scanState ->
            FolderManagerUiState(
                folders = folders,
                scanningFolderIds = scanState.scanningFolderIds,
                queuedFolderIds = scanState.queuedFolderIds,
                error = scanState.error
            )
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                FolderManagerUiState()
            )

    fun setCurrentFolderId(folderId: Long?) {
        _currentFolderId.value = folderId
    }

    fun onSortChange(newSortInfo: SortInfo) {
        _sortInfo.value = newSortInfo
    }

    fun addFolder(path: String) {
        libraryScanManager.addFolderAndScan(path)
    }

    fun deleteFolder(folder: FolderEntity) {
        appScope.launch {
            folderDao.deleteFolderTreePermanently(folder.id)
            libraryIndexRepository.refreshAndPruneIndexes()
        }
    }

    fun refreshFolder(folder: FolderEntity) {
        libraryScanManager.scanFolders(setOf(folder.id))
    }

    fun setFolderIgnored(folder: FolderEntity, ignored: Boolean) {
        appScope.launch {
            folderDao.setIgnoredRecursively(folder.id, ignored)
            libraryIndexRepository.refreshAndPruneIndexes()
        }
    }
}
