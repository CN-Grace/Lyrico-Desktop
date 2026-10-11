package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.lyrico.data.model.artist.ArtistSplitConfig
import com.lonx.lyrico.data.model.entity.AlbumEntity
import com.lonx.lyrico.data.model.entity.ArtistEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.song.tag.ImageMimeTypeDetector
import com.lonx.lyrico.data.utils.ArtistNameSplitter
import com.lonx.lyrico.domain.poster.ArtistPosterEdits
import com.lonx.lyrico.domain.poster.ArtistPosterEmbedding
import com.lonx.lyrico.domain.song.usecase.OverwriteSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsResult
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.artist_poster_folder_error
import com.lonx.lyrico.resources.msg_artist_poster_cancelled
import com.lonx.lyrico.resources.msg_artist_poster_embed_failed
import com.lonx.lyrico.resources.msg_artist_poster_embed_partial
import com.lonx.lyrico.resources.msg_artist_poster_embedded
import com.lonx.lyrico.resources.msg_artist_poster_folder_save_failed
import com.lonx.lyrico.resources.msg_artist_poster_saved_to_folder
import com.lonx.lyrico.resources.msg_read_artist_image_failed
import com.lonx.lyrico.utils.ArtistPosterFileWriter
import com.lonx.lyrico.utils.UiMessage
import com.lonx.lyrico.utils.logging.PlatformLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一张已经读进来的待设置海报。
 *
 * 图片字节只读一次：后续可能要写入多首歌，也可能要落进外置文件夹。
 */
data class ArtistImageSource(
    val picture: AudioPicture,
    val fileName: String?
)

/**
 * 艺术家详情页里「设置艺术家海报」的进度。
 *
 * [message] 是**结果**，只在这一轮结束后才有值；进行中靠 [done]/[total] 说话，
 * 这样界面不需要根据进度反推该显示哪句文案。
 *
 * 桌面端差异：原 Android 版还有一个 `permissionIntentSender` 字段，SAF 拒绝写盘时把
 * `IntentSender` 交给系统授权界面，面板带一个「授予权限」按钮兜着。桌面端的写入结果
 * （`SaveAudioTagsResult`）只有成功与失败两支，没有待授权这一态，所以字段去掉，
 * [hasContent] 也少一个判据。
 *
 * [message] 是 [UiMessage] 而不是已经取好的字符串：文案在组合/协程里才解析，
 * ViewModel 不再需要 `Application`（也就没有「界面语言变了而消息还停在旧语言」的问题）。
 */
data class ArtistPosterEmbedUiState(
    val isRunning: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    /** 正在写的那首歌，进行中给用户一个「确实在动」的凭据。 */
    val activeSong: String? = null,
    val failures: List<String> = emptyList(),
    /** 结果文案（成功/失败/中止），界面直接拿去提示即可。 */
    val message: UiMessage? = null
) {
    val progress: Float get() = if (total <= 0) 0f else done.toFloat() / total

    /** 有内容可展示（进行中，或刚出结果）时才需要占用底部面板。 */
    val hasContent: Boolean
        get() = isRunning || message != null
}

/**
 * 艺术家详情页的数据，以及「给这位艺术家设置海报」的两条写入路径。
 *
 * 两条路径的落点不一样，所以要让用户先选：
 * - [embedArtistImageForAllSongs] 把海报内嵌进这位艺术家每一首歌的标签；
 * - [saveArtistImageToFolder] 按艺术家名存进用户已配置的艺术家海报文件夹（外置兜底）。
 *
 * 桌面端差异（原 Android 版用 SAF 与 `ContentResolver`，Windows 上都不存在）：
 * - 选图给的是 [File]（见 `rememberFileOpenPicker`），直接 `readBytes()`；Android 那边是
 *   单次授权的 `content://` URI，必须当场读完。
 * - 图片 MIME 由 [ImageMimeTypeDetector] 按字节魔数判定，而不是问
 *   `ContentResolver.getType`：同一个选取动作在桌面端没有 provider 可问，而魔数判定
 *   比扩展名更诚实（扩展名骗人时落盘的文件后缀会跟着骗人）。
 * - 海报文件夹是普通目录路径（[File.absolutePath]），不再是 `content://` 树 URI，
 *   因此也不需要 `takePersistableUriPermission`——授权那一套整体消失。
 * - 提示文案以 [UiMessage] 交给界面，不再用 `Toast`。
 */
class ArtistDetailViewModel(
    private val libraryIndexRepository: LibraryIndexRepository,
    private val artistId: Long,
    private val audioTagRepository: AudioTagRepository,
    private val overwriteSongTagsUseCase: OverwriteSongTagsUseCase,
    private val settingsRepository: SettingsRepository,
    private val imageMimeTypeDetector: ImageMimeTypeDetector
) : ViewModel() {

    private val TAG = "ArtistDetailVM"

    val artist: StateFlow<ArtistEntity?> = libraryIndexRepository
        .observeArtistById(artistId)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )

    val songs: StateFlow<List<SongEntity>> = libraryIndexRepository
        .observeSongsByArtistId(artistId)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    val albums: StateFlow<List<AlbumEntity>> = libraryIndexRepository
        .observeAlbumsByArtistId(artistId)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    /** 已配置的艺术家海报文件夹；界面用它决定「存进文件夹」是否已有去处。 */
    val artistPosterFolder: StateFlow<String?> = settingsRepository.artistPosterFolder
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )

    /**
     * 海报的归属靠图片描述记录，而描述要和艺术家字段的拆分规则
     * （[ArtistNameSplitter]）对齐，所以这里读的是同一份配置。
     */
    private val artistSplitConfig = settingsRepository.artistSplitConfigFlow
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ArtistSplitConfig()
        )

    private val _artistPosterEmbedState = MutableStateFlow(ArtistPosterEmbedUiState())
    val artistPosterEmbedState: StateFlow<ArtistPosterEmbedUiState> = _artistPosterEmbedState.asStateFlow()

    private var artistPosterJob: Job? = null

    /**
     * 读取用户在文件选择器里选中的本地图片。
     *
     * 读失败时只发一条提示，不调用回调——界面因此不需要自己判断「选中的图能不能用」。
     */
    fun prepareArtistImage(file: File, onReady: (ArtistImageSource) -> Unit) {
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) {
                try {
                    // Android 走 `contentResolver.openInputStream`，返回 null 即「读不到」；
                    // 桌面端读不到就是文件不在或不是普通文件，报文一模一样。
                    if (!file.isFile) {
                        null
                    } else {
                        val bytes = file.readBytes()
                        if (bytes.isEmpty()) {
                            null
                        } else {
                            ArtistImageSource(
                                picture = AudioPicture(
                                    data = bytes,
                                    mimeType = imageMimeTypeDetector.detect(bytes),
                                    description = "",
                                    pictureType = AudioPictureType.Artist.tagLibName
                                ),
                                fileName = file.name
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlatformLog.e(TAG, "读取艺术家海报失败: ${file.absolutePath}", e)
                    null
                }
            }

            if (source == null) {
                _artistPosterEmbedState.update {
                    it.copy(message = UiMessage.Localized(Res.string.msg_read_artist_image_failed))
                }
            }
            if (source != null) onReady(source)
        }
    }

    /**
     * 把 [source] 内嵌进这位艺术家的每一首歌。
     *
     * 每首歌先按它自己的艺术家字段算出图片描述（见 [ArtistPosterEmbedding]）；
     * 标签里已经是这张图的歌直接跳过，所以重跑这一炉只会补上没写成的那些。
     */
    fun embedArtistImageForAllSongs(
        artistName: String,
        targetSongs: List<SongEntity>,
        source: ArtistImageSource
    ) {
        val name = artistName.trim()
        if (name.isEmpty() || targetSongs.isEmpty()) return

        artistPosterJob?.cancel()
        _artistPosterEmbedState.value = ArtistPosterEmbedUiState(
            isRunning = true,
            total = targetSongs.size
        )

        artistPosterJob = viewModelScope.launch {
            val failures = mutableListOf<String>()
            var done = 0

            try {
                for ((index, song) in targetSongs.withIndex()) {
                    _artistPosterEmbedState.update {
                        it.copy(done = index, activeSong = song.displayTitle())
                    }

                    when (writeArtistPicture(song, artistName = name, source = source.picture)) {
                        is SaveAudioTagsResult.Failed -> failures += song.displayTitle()
                        is SaveAudioTagsResult.Success -> Unit
                    }

                    done = index + 1
                    _artistPosterEmbedState.update {
                        it.copy(done = done, failures = failures.toList())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLog.e(TAG, "写入艺术家海报时出错", e)
                _artistPosterEmbedState.update {
                    it.copy(
                        isRunning = false,
                        activeSong = null,
                        failures = failures.toList(),
                        message = UiMessage.Localized(Res.string.msg_artist_poster_embed_failed)
                    )
                }
                return@launch
            }

            _artistPosterEmbedState.update {
                it.copy(
                    isRunning = false,
                    done = done,
                    activeSong = null,
                    failures = failures.toList(),
                    message = if (failures.isEmpty()) {
                        UiMessage.Localized(Res.string.msg_artist_poster_embedded, done)
                    } else {
                        UiMessage.Localized(
                            Res.string.msg_artist_poster_embed_partial,
                            done - failures.size,
                            failures.size
                        )
                    }
                )
            }
        }
    }

    /**
     * 把 [source] 按艺术家名存进艺术家海报文件夹。
     *
     * 没有可用文件夹时写入会失败并提示：用户点这一项之前，界面会先带他去添加文件夹。
     */
    fun saveArtistImageToFolder(artistName: String, source: ArtistImageSource) {
        val name = artistName.trim()
        if (name.isEmpty()) return

        artistPosterJob?.cancel()
        _artistPosterEmbedState.value = ArtistPosterEmbedUiState(
            isRunning = true,
            total = 1,
            activeSong = source.fileName
        )

        artistPosterJob = viewModelScope.launch {
            val writtenName = try {
                val folder = settingsRepository.artistPosterFolder.first()
                ArtistPosterFileWriter.write(
                    artistName = name,
                    data = source.picture.data,
                    mimeType = source.picture.mimeType,
                    posterFolder = folder
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLog.e(TAG, "保存艺术家海报文件失败", e)
                null
            }

            // 外置海报是按文件名找的，写完要让取图链路重新去读一次
            if (writtenName != null) {
                refreshArtistPostersSafely()
            }

            _artistPosterEmbedState.update {
                it.copy(
                    isRunning = false,
                    done = if (writtenName != null) 1 else 0,
                    activeSong = null,
                    message = if (writtenName != null) {
                        UiMessage.Localized(
                            Res.string.msg_artist_poster_saved_to_folder,
                            writtenName
                        )
                    } else {
                        UiMessage.Localized(Res.string.msg_artist_poster_folder_save_failed)
                    }
                )
            }
        }
    }

    fun cancelArtistPosterWrite() {
        artistPosterJob?.cancel()
        artistPosterJob = null
        _artistPosterEmbedState.update {
            it.copy(
                isRunning = false,
                activeSong = null,
                message = UiMessage.Localized(Res.string.msg_artist_poster_cancelled)
            )
        }
    }

    /**
     * 登记新的艺术家海报文件夹。
     *
     * 写设置与刷新取图链路都完成后再回调 [onAdded]：界面借此在「先选文件夹、再选海报」的
     * 顺序里接着把选图器打开，避免用户在设置还没落盘时就选了海报。
     *
     * Android 版另外还要在回调里 `takePersistableUriPermission`；桌面端拿到的是普通目录
     * 路径，不需要这一步。
     */
    fun setArtistPosterFolder(path: String, onAdded: () -> Unit) {
        artistPosterJob = viewModelScope.launch {
            try {
                settingsRepository.setArtistPosterFolder(path)
                refreshArtistPostersSafely()
                onAdded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlatformLog.e(TAG, "添加艺术家海报文件夹失败", e)
                _artistPosterEmbedState.update {
                    it.copy(message = UiMessage.Localized(Res.string.artist_poster_folder_error))
                }
            }
        }
    }

    /** 关掉进度面板：丢掉这一轮的结果，下次打开重新开始。 */
    fun clearArtistPosterStatus() {
        if (_artistPosterEmbedState.value.isRunning) return
        _artistPosterEmbedState.value = ArtistPosterEmbedUiState()
    }

    fun clearArtistPosterMessage() {
        _artistPosterEmbedState.update { it.copy(message = null) }
    }

    /**
     * 把 [source] 写进 [song]：读它当前的标签，按它自己的艺术家字段算出描述，再整份写回去。
     *
     * 读完整份覆盖（而不是只补一张图）是刻意的：写下去的图片列表必须来自同一份读到内存的数据，
     * 只改其中一张会让「写进去的图」和「读出来的图」对不上，标签里的其它图片也会被动到。
     */
    private suspend fun writeArtistPicture(
        song: SongEntity,
        artistName: String,
        source: AudioPicture
    ): SaveAudioTagsResult {
        val current = audioTagRepository.read(song.uri)
        val artistNames = ArtistNameSplitter.splitArtists(current.artist, artistSplitConfig.value)
        val binding = ArtistPosterEmbedding.plan(
            pictures = current.pictures,
            artistNames = artistNames,
            artistName = artistName,
            data = source.data,
            mimeType = source.mimeType
        ) ?: return SaveAudioTagsResult.Success(song = song, tagData = current)

        val picture = source.copy(
            description = binding.description,
            pictureType = AudioPictureType.Artist.tagLibName
        )
        val nextPictures = ArtistPosterEdits.setFor(
            pictures = current.pictures,
            artistNames = artistNames,
            artistName = artistName,
            picture = picture
        )

        return overwriteSongTagsUseCase(
            uri = song.uri,
            tagData = current.copy(pictures = nextPictures),
            picturesAuthored = true
        )
    }

    private fun SongEntity.displayTitle(): String =
        title?.takeIf { it.isNotBlank() } ?: fileName

    private suspend fun refreshArtistPostersSafely() {
        try {
            settingsRepository.refreshArtistPosters()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlatformLog.w(TAG, "刷新艺术家海报缓存失败", e)
        }
    }
}
