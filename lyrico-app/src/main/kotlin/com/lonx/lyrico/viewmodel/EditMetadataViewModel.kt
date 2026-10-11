package com.lonx.lyrico.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.audiotag.model.AudioTagData
import com.lonx.audiotag.model.CustomTagField
import com.lonx.audiotag.model.frontCoverOrFallback
import com.lonx.audiotag.model.pictureOfType
import com.lonx.audiotag.model.removePictureType
import com.lonx.audiotag.model.replacePicture
import com.lonx.lyrico.data.editfield.CustomTagKey
import com.lonx.lyrico.data.editfield.EditFieldConfigRepository
import com.lonx.lyrico.data.editfield.EditFieldDefinition
import com.lonx.lyrico.data.editfield.EditFieldRegistry
import com.lonx.lyrico.data.editfield.EditFieldScene
import com.lonx.lyrico.data.model.ConversionMode
import com.lonx.lyrico.data.model.artist.ArtistSplitConfig
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.lyrics.LyricFormat
import com.lonx.lyrico.data.model.lyrics.LyricRenderConfig
import com.lonx.lyrico.data.model.lyrics.LyricsProcessingOptions
import com.lonx.lyrico.data.model.lyrics.sanitizeStandardFields
import com.lonx.lyrico.data.model.metadata.MetadataApplyPolicy
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.metadata.MetadataWriteMode
import com.lonx.lyrico.data.model.metadata.SearchResultApplier
import com.lonx.lyrico.data.model.metadata.StandardPluginField
import com.lonx.lyrico.data.model.plugin.GlobalFieldProcessSettings
import com.lonx.lyrico.data.model.plugin.defaultPluginFieldProcessConfig
import com.lonx.lyrico.data.model.search.LyricsSearchResult
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.PlaybackRepository
import com.lonx.lyrico.data.repository.PlaybackResult
import com.lonx.lyrico.data.repository.SettingsDefaults
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.utils.ArtistNameSplitter
import com.lonx.lyrico.domain.poster.ArtistPosterEdits
import com.lonx.lyrico.domain.song.usecase.OverwriteSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.ReadAudioTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsResult
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.msg_load_same_album_cover_failed
import com.lonx.lyrico.resources.msg_no_same_album_cover_found
import com.lonx.lyrico.resources.msg_same_album_cover_applied
import com.lonx.lyrico.resources.no_player_found
import com.lonx.lyrico.resources.replay_gain_calculate_cancelled
import com.lonx.lyrico.resources.replay_gain_calculate_failed
import com.lonx.lyrico.resources.replay_gain_calculate_success
import com.lonx.lyrico.resources.replay_gain_error_codec_exception
import com.lonx.lyrico.resources.replay_gain_error_general
import com.lonx.lyrico.resources.replay_gain_error_no_audio_track
import com.lonx.lyrico.resources.replay_gain_error_unknown_mime_type
import com.lonx.lyrico.resources.replay_gain_error_zero_sample_count
import com.lonx.lyrico.resources.unknown_error
import com.lonx.lyrico.utils.CoverSourceType
import com.lonx.lyrico.utils.LyricDecoder
import com.lonx.lyrico.utils.LyricEncoder
import com.lonx.lyrico.utils.PluginFieldPostProcessor
import com.lonx.lyrico.utils.ReplayGainCalculateState
import com.lonx.lyrico.utils.ReplayGainError
import com.lonx.lyrico.utils.ReplayGainScanner
import com.lonx.lyrico.utils.SiblingFileWriter
import com.lonx.lyrico.utils.UiMessage
import com.lonx.lyrico.utils.getCoverSourceType
import com.lonx.lyrico.utils.logging.PlatformLog
import com.lonx.lyrico.utils.lyrics.LyricsColumnSorter
import com.lonx.lyrico.utils.lyrics.LyricsTextCleanup
import com.lonx.lyrico.utils.lyrics.document.LyricsDocumentPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.URL
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

data class EditMetadataUiState(
    val songInfo: SongInfo? = null,

    val originalTagData: AudioTagData? = null,
    val editingTagData: AudioTagData? = null,

    val replayGainCalculateProgress: Float? = null,
    val isEditing: Boolean = false,
    val fileName: String? = null,

    /**
     * 编辑态封面（只要不为 null，就代表用户替换过封面）
     */
    val coverUri: Any? = null,
    val exportCoverResult: Boolean? = null,
    val exportArtistImageResult: Boolean? = null,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean? = null,
    val originalCover: Any? = null,
    val picture: AudioPicture? = null,
    val isReplayGainCalculating: Boolean = false,
    val replayGainScanMessage: UiMessage? = null,
    val saveFailureMessage: String? = null,
    val saveFailureLogText: String? = null,

    /**
     * 歌词导出导入状态
     */
    val exportLyricsResult: Boolean? = null,
    val importLyricsResult: Boolean? = null,

    /**
     * 同专辑封面加载结果消息
     */
    val sameAlbumCoverMessage: UiMessage? = null,

    /**
     * 「播放音乐」的结果消息。
     *
     * Android 直接弹 Toast；桌面端和 [sameAlbumCoverMessage] 一样把结果留在状态里，由界面弹 snackbar。
     */
    val playMessage: UiMessage? = null,
)

/**
 * The single-song metadata editor: it reads a file's tags, edits them in memory and writes them back.
 *
 * The Android version was the port's heaviest `Context` consumer, and almost all of that is gone
 * rather than emulated:
 *
 * - **`Context` and `Uri` leave every signature.** Android needed a `ContentResolver` to read the
 *   picked image, to write an export and to ask the storage framework for a sibling document; on
 *   Windows the picker seams ([com.lonx.lyrico.platform.FileOpenPicker] /
 *   [com.lonx.lyrico.platform.FileSavePicker]) hand this class a real [File]. Songs are identified by
 *   their absolute path everywhere (`songs.uri`), so `uriString` keeps its Android name but *is* a
 *   Windows path -- the same reading [com.lonx.lyrico.utils.ReplayGainScanner] already gives it.
 * - **The permission flow is deleted, not ported.** Android's `SaveAudioTagsResult` had a
 *   `PermissionRequired` case carrying an `IntentSender`, and `saveMetadata` caught
 *   `RequiresUserPermissionException` / `RecoverableSecurityException` to hand that sender to the
 *   screen. The ported [SaveAudioTagsResult] has only `Success` and `Failed` -- the app owns the files
 *   it writes -- so the state field, both catch arms, the `Build.VERSION.SDK_INT` check and
 *   `consumePermissionRequest()` all go with it.
 * - **MediaStore is gone.** Android exported an artist poster through
 *   `MediaStore.Images.Media.insert` into `Pictures/ArtistPosters`; on desktop the destination is the
 *   save dialog the screen owns, so [exportArtistImage] writes the file the user picked and
 *   [getArtistImageFileName] keeps the Android name (`Artist_<timestamp>.jpg`) as the dialog default.
 * - **`play(context)` became [play]**, funnelling [PlaybackRepository]'s system-association result
 *   through `SongSelectionViewModel`'s message mapping and reporting it in
 *   [EditMetadataUiState.playMessage] -- the `sameAlbumCoverMessage` shape, because there is no
 *   `Toast` to draw.
 * - **Pictures are Compose `ImageBitmap`s.** Android's crop result was a `Bitmap` whose *bytes* went
 *   into the tag and into the UI state; here the [ImageBitmap] is what the state displays (that is
 *   where `CoverSourceType.IMAGE_BITMAP` gets its producer) and it is encoded to JPEG for the tag with
 *   `ImageIO` -- the encoder [com.lonx.lyrico.worker.processor.BatchExportProcessor] and the export
 *   tests already use.
 * - **Three Android leftovers are not ported**: `LyricsFormatConversionSession` with its
 *   `getOrCreateLyricsFormatConversionSession()` accessor, which nothing ever called (the field was
 *   only ever assigned); `LYRICS_EXPORT_MIME_TYPE`, which existed only to type a SAF document; and
 *   the `updateCover(uri: Uri)` overload, which no caller ever reached (the screen always picked
 *   through the `context` overload), so [updateCover] has one file-carrying entry point instead of two.
 * - **The two lyric file paths moved off the calling thread.** Android read and wrote them on the
 *   main dispatcher; file IO on Windows is slow enough to be visible, so both now run on
 *   [Dispatchers.IO] like every other export in the port.
 *
 * Everything else is the Android behaviour, including the parts that look odd: `readMetadata` leaves
 * the editing state alone when the user is already editing, `filterHiddenEditFields` copies hidden
 * fields back from the original rather than dropping them, `revertCover` re-inserts the original front
 * cover *after* removing the current one, and every failure both updates the UI state and writes an
 * app log entry.
 */
class EditMetadataViewModel(
    private val songLibraryRepository: SongLibraryRepository,
    private val readAudioTagsUseCase: ReadAudioTagsUseCase,
    private val overwriteSongTagsUseCase: OverwriteSongTagsUseCase,
    private val settingsRepository: SettingsRepository,
    private val playbackRepository: PlaybackRepository,
    private val replayGainScanner: ReplayGainScanner,
    private val appLogRepository: AppLogRepository,
    private val editFieldConfigRepository: EditFieldConfigRepository,
) : ViewModel() {

    private val TAG = "EditMetadataVM"
    val limitLyricsInputLines = settingsRepository.limitLyricsInputLines.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SettingsDefaults.LIMIT_LYRICS_INPUT_LINES
    )
    private val lyricRenderConfig = settingsRepository.lyricRenderConfigFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        null
    )

    /**
     * 艺术家拆分配置。
     *
     * 艺术家海报用图片描述记录归属，需要先把艺术家字段拆成一个个艺术家才能判断
     * 每张海报属于谁，所以这里的规则和艺术家视图保持一致。
     */
    val artistSplitConfig: StateFlow<ArtistSplitConfig> =
        settingsRepository.artistSplitConfigFlow.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ArtistSplitConfig()
        )

    private var currentSong: SongEntity? = null

    // 存储当前正在操作的歌曲路径字符串
    private var currentSongUri: String? = null
    private var preOffsetLyrics: String? = null
    private var scanJob: Job? = null
    // 记录当前的累计偏移量，供 UI 显示
    private val _currentShiftOffset = MutableStateFlow(0L)
    val currentShiftOffset: StateFlow<Long> = _currentShiftOffset.asStateFlow()
    private val _uiState = MutableStateFlow(EditMetadataUiState())
    val uiState: StateFlow<EditMetadataUiState> = _uiState.asStateFlow()

    /** 单曲编辑页按配置排序后的可见字段。 */
    val visibleFields: StateFlow<List<EditFieldDefinition>> =
        editFieldConfigRepository.configFlow
            .map { config ->
                config.visibleFieldsForScene(EditFieldScene.SingleEdit)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    fun readMetadata(uriString: String) {
        currentSongUri = uriString

        viewModelScope.launch {
            try {
                // 1. 获取数据库实体
                val song = songLibraryRepository.getSongByUri(uriString)
                currentSong = song

                // 2. 读取文件标签
                val audioTagData = readAudioTagsUseCase(uriString)
                val displayFileName = song?.fileName ?: audioTagData.fileName
                val displayPicture = audioTagData.pictures.frontCoverOrFallback()
                val displayCover = displayPicture?.data

                _uiState.update { state ->
                    state.copy(
                        songInfo = SongInfo(
                            uriString = uriString,
                            tagData = audioTagData
                        ),
                        originalTagData = audioTagData,
                        fileName = displayFileName.substringBeforeLast(
                            ".",
                            missingDelimiterValue = displayFileName
                        ),
                        // 如果当前没有在编辑，才重置 editingTagData
                        editingTagData = if (state.isEditing) state.editingTagData else audioTagData,
                        picture = displayPicture,
                        originalCover = if (state.isEditing) state.originalCover else displayCover,
                        coverUri = if (state.isEditing) state.coverUri else displayCover
                    )
                }
            } catch (e: Exception) {
                PlatformLog.e(TAG, "读取音频元数据失败: $uriString", e)
                recordMetadataException(
                    message = "Failed to read metadata",
                    relatedId = uriString,
                    throwable = e
                )
            }
        }
    }

    fun updateTag(block: AudioTagData.() -> AudioTagData) {
        _uiState.update { state ->
            val current = state.editingTagData ?: return@update state
            state.copy(
                editingTagData = current.block(),
                isEditing = true
            )
        }
    }

    fun updateCustomFieldValue(key: String, value: String) {
        val normalizedKey = normalizeCustomTagKey(key) ?: return

        updateTag {
            val fields = customFields.toMutableList()
            val index = fields.indexOfFirst { it.key.equals(normalizedKey, ignoreCase = true) }

            if (index >= 0) {
                fields[index] = fields[index].copy(key = normalizedKey, value = value)
            } else {
                fields += CustomTagField(
                    key = normalizedKey,
                    value = value,
                )
            }

            copy(customFields = fields)
        }
    }

    fun removeCustomFieldValue(key: String) {
        val normalizedKey = normalizeCustomTagKey(key) ?: return

        updateTag {
            copy(
                customFields = customFields.filterNot { it.key.equals(normalizedKey, ignoreCase = true) }
            )
        }
    }

    fun revertCustomField(key: String) {
        val normalizedKey = normalizeCustomTagKey(key) ?: return
        val original = _uiState.value.originalTagData
            ?.customFields
            .orEmpty()
            .firstOrNull { it.key.equals(normalizedKey, ignoreCase = true) }
            ?.copy(key = normalizedKey)

        updateTag {
            val fields = customFields
                .filterNot { it.key.equals(normalizedKey, ignoreCase = true) }
                .toMutableList()

            if (original != null) {
                fields += original
            }

            copy(customFields = fields)
        }
    }

    fun addCustomFieldAndShow(key: String, value: String) {
        val normalizedKey = normalizeCustomTagKey(key) ?: return

        viewModelScope.launch {
            editFieldConfigRepository.addCustomTag(normalizedKey)
        }

        updateCustomFieldValue(normalizedKey, value)
    }

    private fun normalizeCustomTagKey(input: String): String? = CustomTagKey.normalize(input)

    /**
     * 打开弹窗前准备：拍快照，并重置累计偏移量
     */
    fun prepareLyricsOffset() {
        preOffsetLyrics = _uiState.value.editingTagData?.lyrics
        _currentShiftOffset.value = 0L
    }

    fun setLimitLyricsInputLines(limit: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveLimitLyricsInputLines(limit)
        }
    }

    /**
     * 应用绝对偏移量（相对于快照）
     */
    fun applyLyricsOffset(totalOffset: Long) {
        val originalLyrics = preOffsetLyrics ?: return

        // 1. 更新当前显示的数值
        _currentShiftOffset.value = totalOffset

        // 2. 永远基于 originalLyrics (快照) 进行偏移，避免来回计算导致的精度丢失或触底失真
        val shiftedLyrics = LyricEncoder.shiftLyricsOffset(originalLyrics, totalOffset)

        _uiState.update { state ->
            state.copy(
                editingTagData = state.editingTagData?.copy(lyrics = shiftedLyrics),
                isEditing = true
            )
        }
    }

    /**
     * 重置回打开 BottomSheet 时的状态
     */
    fun resetLyricsOffset() {
        _currentShiftOffset.value = 0L
        preOffsetLyrics?.let { original ->
            _uiState.update { state ->
                state.copy(
                    editingTagData = state.editingTagData?.copy(lyrics = original)
                )
            }
        }
    }

    fun updateMetadataFromSearchResult(result: LyricsSearchResult) {
        _uiState.update { state ->
            val current = state.editingTagData ?: AudioTagData()

            val renderConfig = lyricRenderConfig.value
            val fieldProcessor = PluginFieldPostProcessor(
                GlobalFieldProcessSettings(
                    scriptConversion = renderConfig?.conversionMode ?: ConversionMode.NONE,
                    removeEmptyLines = renderConfig?.removeEmptyLines ?: SettingsDefaults.REMOVE_EMPTY_LINES
                )
            )
            val rawFields = result.normalizedFields() +
                    result.lyrics?.takeIf { it.isNotBlank() }?.let { mapOf("lyrics" to it) }.orEmpty()
            val processedFields = fieldProcessor.processFields(
                pluginId = result.pluginId,
                fields = rawFields.sanitizeStandardFields(),
                config = defaultPluginFieldProcessConfig(result.pluginId),
                fieldDefinitions = emptyList(),
                writeRules = emptyList()
            )
            val applyTargets = when {
                result.applyTargets.isNotEmpty() -> result.applyTargets
                else -> processedFields.keys
                    .mapNotNull { key -> StandardPluginField.fromKey(key)?.target }
                    .toSet()
            }
            val applyPolicy = MetadataApplyPolicy(
                applyTargets.associateWith { MetadataWriteMode.OVERWRITE }
            )
            val applied = SearchResultApplier.applyFields(
                current = current,
                fields = processedFields,
                policy = applyPolicy
            )
            val nextCoverUri = if (applyPolicy.modeOf(MetadataFieldTarget.COVER) != MetadataWriteMode.DISABLED) {
                applied.picUrl
                    ?.takeIf { it.isNotBlank() && it != current.picUrl }
                    ?: state.coverUri
            } else {
                state.coverUri
            }
            state.copy(
                isEditing = true,
                editingTagData = applied,
                coverUri = nextCoverUri
            )
        }
    }

    /**
     * 更新封面（从本地选择）
     *
     * 展示来源存的是**文件路径**而不是 [File]：状态里的 `coverUri` 只允许字节数组、路径/网址字符串
     * 和 [ImageBitmap] 三种，多一种会让「这是哪类来源」的判断多一条分支（见 [CoverSourceType]）。
     */
    fun updateCover(file: File) {
        readPictureBytes(
            file = file,
            onResult = { bytes, mimeType ->
                if (bytes == null) return@readPictureBytes

                val audioPicture = AudioPicture(
                    data = bytes,
                    mimeType = mimeType,
                    description = "",
                    pictureType = AudioPictureType.FrontCover.tagLibName
                )

                _uiState.update { state ->
                    state.withUpdatedFrontCover(
                        displaySource = file.absolutePath,
                        audioPicture = audioPicture
                    )
                }
            }
        )
    }

    fun updateCover(picUrl: String) {
        _uiState.update { state ->
            state.copy(
                coverUri = picUrl,
                isEditing = true,
                editingTagData = state.editingTagData?.copy(picUrl = picUrl)
            )
        }
    }

    /**
     * 更新封面（裁剪结果）
     *
     * Android 把裁剪出的 `Bitmap` 压成 JPEG 字节后，**字节数组**既是标签内容也是界面显示来源；桌面端
     * 显示来源换成 [ImageBitmap]（界面层全程用它的代价是这里要再编码一次，见 [toJpegBytesOrNull]）。
     */
    fun updateCover(image: ImageBitmap) {
        val byteArray = image.toJpegBytesOrNull() ?: return
        val audioPicture = AudioPicture(
            data = byteArray,
            mimeType = "image/jpeg",
            description = "",
            pictureType = AudioPictureType.FrontCover.tagLibName
        )

        _uiState.update { state ->
            state.withUpdatedFrontCover(
                displaySource = image,
                audioPicture = audioPicture
            )
        }
    }

    // ---------------------------------------------------------------- 艺术家海报

    /**
     * 新增/替换 [artistName] 的艺术家海报：该艺术家名下原有的海报会被替换掉。
     *
     * 一个艺术家只保留一张海报（ID3v2 规定同一个 content descriptor 只能挂一张图），
     * 所以「从本地选择」既是新增也是替换；[artistName] 会写进图片描述作为归属。
     */
    fun setArtistImage(file: File, artistName: String) {
        readPictureBytes(
            file = file,
            onResult = { bytes, mimeType ->
                if (bytes == null) return@readPictureBytes
                val picture = newArtistPicture(bytes, mimeType, artistName)
                updateArtistPictures { pictures, _ ->
                    ArtistPosterEdits.setFor(
                        pictures = pictures,
                        artistNames = currentArtistNames(),
                        artistName = artistName,
                        picture = picture
                    )
                }
            }
        )
    }

    /** 移除某一张艺术家图片（标签里若残留多张，只删这一张）。 */
    fun removeArtistImage(target: AudioPicture) {
        updateArtistPictures { pictures, _ -> ArtistPosterEdits.remove(pictures, target) }
    }

    /** 裁剪结果：替换某一张艺术家图片的数据，保留它的归属描述。 */
    fun replaceArtistImage(image: ImageBitmap, target: AudioPicture) {
        val bytes = image.toJpegBytesOrNull() ?: return
        updateArtistPictures { pictures, _ ->
            ArtistPosterEdits.replaceData(pictures, target, bytes, "image/jpeg")
        }
    }

    /**
     * 把 [target] 这一组艺术家海报改挂到 [artistName]。
     *
     * 描述对不上任何艺术家时用户需要在这里重新指定；该艺术家原有的海报会被替换掉。
     * 目标艺术家已有海报且用户想两张都留下时用 [swapArtistImages]。
     */
    fun reassignArtistImages(target: AudioPicture, artistName: String) {
        updateArtistPictures { pictures, _ ->
            ArtistPosterEdits.reassign(
                pictures = pictures,
                artistNames = currentArtistNames(),
                target = target,
                artistName = artistName
            )
        }
    }

    /** [target] 与 [artistName] 原有的海报**交换归属**，两张图都留着。 */
    fun swapArtistImages(target: AudioPicture, artistName: String) {
        updateArtistPictures { pictures, _ ->
            ArtistPosterEdits.swapOwners(
                pictures = pictures,
                artistNames = currentArtistNames(),
                target = target,
                artistName = artistName
            )
        }
    }

    /**
     * 导出某张艺术家海报到 [destination]。
     *
     * Android 这里写系统相册（MediaStore + `Pictures/ArtistPosters`），桌面端的落点是保存对话框，
     * 目标路径由界面层先问用户要（默认名字见 [getArtistImageFileName]）。
     */
    fun exportArtistImage(target: AudioPicture, destination: File) {
        exportPictureToFile(
            source = target.data,
            destination = destination,
            failureLogMessage = "Failed to export artist poster",
            onResult = { success -> _uiState.update { it.copy(exportArtistImageResult = success) } }
        )
    }

    /**
     * 艺术家海报导出对话框的默认文件名。
     *
     * Android 把 `Artist_<时间戳>.jpg` 拼进 MediaStore 的 `DISPLAY_NAME`；桌面端名字由用户在保存
     * 对话框里定，所以提前算出来当默认值，命名规则不变。
     */
    fun getArtistImageFileName(): String = "Artist_${System.currentTimeMillis()}.jpg"

    private fun newArtistPicture(
        bytes: ByteArray,
        mimeType: String,
        artistName: String
    ) = AudioPicture(
        data = bytes,
        mimeType = mimeType,
        description = artistName.trim(),
        pictureType = AudioPictureType.Artist.tagLibName
    )

    /** 当前编辑态的艺术家名（艺术家字段按配置拆分）。 */
    private fun currentArtistNames(): List<String> =
        _uiState.value.editingTagData?.let {
            ArtistNameSplitter.splitArtists(it.artist, artistSplitConfig.value)
        }.orEmpty()

    /** 用 [transform] 生成新的图片列表；结果没变时不动状态。 */
    private fun updateArtistPictures(
        transform: (pictures: List<AudioPicture>, original: AudioTagData?) -> List<AudioPicture>
    ) {
        _uiState.update { state ->
            val current = state.editingTagData ?: return@update state
            val nextPictures = transform(current.pictures, state.originalTagData)
            if (nextPictures == current.pictures) return@update state
            state.copy(
                isEditing = true,
                editingTagData = current.copy(pictures = nextPictures)
            )
        }
    }

    /** 读取本地图片字节；失败时记录日志并以 `null` 回调。 */
    private fun readPictureBytes(
        file: File,
        onResult: (bytes: ByteArray?, mimeType: String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Android 走 `contentResolver.openInputStream`，返回 null 即「读不到」；桌面端读不到
                // 就是文件不在或不是普通文件，报文一模一样。
                if (!file.isFile) {
                    recordMetadataFailure(
                        message = "Failed to read image source",
                        relatedId = currentSongUri,
                        detail = "Source file: ${file.absolutePath}"
                    )
                    onResult(null, "image/jpeg")
                    return@launch
                }

                val bytes = file.readBytes()
                // Android 问 `ContentResolver.getType`，桌面端没有这一层：按扩展名判断，认不出来时
                // 也退回 `image/jpeg`（Android 对非 `image/*` 的类型同样是这个退路）。
                onResult(bytes, file.imageMimeType())
            } catch (e: Exception) {
                PlatformLog.e(TAG, "读取图片失败: ${file.absolutePath}", e)
                recordMetadataException(
                    message = "Failed to read image source",
                    relatedId = currentSongUri,
                    throwable = e
                )
                onResult(null, "image/jpeg")
            }
        }
    }

    /**
     * 图片文件的 MIME 类型，按扩展名判断。
     */
    private fun File.imageMimeType(): String = when (extension.lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    /**
     * 把界面层的 [ImageBitmap] 编码成 JPEG 字节（标签里的封面/海报只存编码后的图片）。
     *
     * Android 交给 `Bitmap.compress(JPEG, 100, ...)`，桌面端交给 `ImageIO`：先经
     * [toAwtImage] 拿到 AWT 图像，再摊到 `TYPE_INT_RGB`（JPEG 没有透明通道，带 alpha 的
     * `TYPE_INT_ARGB` 会被 writer 拒掉），最后按质量 1.0 写 jpg（`ImageIO` 默认是 0.75，会把
     * 用户刚裁好的 JPEG 再压一遍，和 Android 的 100 对不上）。
     *
     * 正常 JDK 一定带 JPEG writer；真缺失时记日志并返回 null，调用方按「这张图没法写」处理，
     * 而不是抛异常打断界面（Android 那边 `compress` 返回 false 也是同一种落空）。
     */
    private fun ImageBitmap.toJpegBytesOrNull(): ByteArray? = try {
        val awt = toAwtImage()
        val rgb = BufferedImage(awt.width, awt.height, BufferedImage.TYPE_INT_RGB)
        val graphics = rgb.createGraphics()
        graphics.drawImage(awt, 0, 0, null)
        graphics.dispose()
        ByteArrayOutputStream().use { stream ->
            val writer = ImageIO.getImageWritersByFormatName("jpg")
                .let { if (it.hasNext()) it.next() else null }
                ?: error("No JPEG writer available")
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 1.0f
            }
            MemoryCacheImageOutputStream(stream).use { imageOutput ->
                writer.output = imageOutput
                writer.write(null, IIOImage(rgb, null, null), params)
            }
            writer.dispose()
            stream.toByteArray()
        }
    } catch (e: Exception) {
        PlatformLog.e(TAG, "封面图片编码失败", e)
        null
    }

    /** 把标签里的图片字节解码成界面层用的 [ImageBitmap]；解不出来返回 null。 */
    private fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
        ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()

    private fun EditMetadataUiState.withUpdatedFrontCover(
        displaySource: Any?,
        audioPicture: AudioPicture
    ): EditMetadataUiState {
        val current = editingTagData ?: AudioTagData()
        return copy(
            coverUri = displaySource,
            picture = audioPicture,
            isEditing = true,
            editingTagData = current.copy(
                pictures = current.pictures.replacePicture(
                    picture = audioPicture,
                    type = AudioPictureType.FrontCover
                ),
                picUrl = null
            )
        )
    }

    /**
     * 移除封面
     */
    fun removeFrontCover() {
        _uiState.update { state ->
            val current = state.editingTagData ?: return@update state
            val newPictures = current.pictures.removePictureType(AudioPictureType.FrontCover)
            val displayPicture = newPictures.frontCoverOrFallback()

            state.copy(
                coverUri = displayPicture?.data,
                picture = displayPicture,
                isEditing = true,
                editingTagData = current.copy(
                    pictures = newPictures,
                    picUrl = ""
                )
            )
        }
    }

    fun getCoverFileName(): String = "${_uiState.value.fileName ?: "Cover"}.jpg"

    fun exportCover(destination: File) {
        exportPictureToFile(
            source = currentCoverSource(),
            destination = destination,
            failureLogMessage = "Failed to export cover",
            onResult = { success -> _uiState.update { it.copy(exportCoverResult = success) } }
        )
    }

    fun exportCoverToAudioDirectory() {
        val source = currentCoverSource()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val audioFile = currentAudioFile()
                if (source == null || audioFile == null) {
                    recordMetadataFailure(
                        message = "Failed to export cover next to audio: source unavailable",
                        relatedId = currentSongUri
                    )
                    _uiState.update { it.copy(exportCoverResult = false) }
                    return@launch
                }

                SiblingFileWriter.write(audioFile, getCoverFileName()) { outputStream ->
                    if (!writePictureSource(outputStream, source)) {
                        throw IllegalStateException("Cover source stream unavailable")
                    }
                }
                _uiState.update { it.copy(exportCoverResult = true) }
            } catch (e: Exception) {
                PlatformLog.e(TAG, "导出封面到音频同目录失败", e)
                recordMetadataException(
                    message = "Failed to export cover next to audio",
                    relatedId = currentSongUri,
                    throwable = e
                )
                _uiState.update { it.copy(exportCoverResult = false) }
            }
        }
    }

    private fun currentCoverSource(): Any? {
        val state = _uiState.value
        return state.coverUri ?: state.originalCover ?: state.picture?.data
    }

    /**
     * 当前歌曲在磁盘上的文件。
     *
     * Android 要把 `uri` 转成 `Document Uri` 才能交给 `SafSiblingFileWriter` 找同目录；桌面端
     * `songs.uri` 就是绝对路径，直接当 [File] 用。
     */
    private fun currentAudioFile(): File? {
        val path = currentSong?.uri ?: currentSongUri ?: return null
        return File(path)
    }

    private fun exportPictureToFile(
        source: Any?,
        destination: File,
        failureLogMessage: String,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (source == null) {
                    recordMetadataFailure(
                        message = "$failureLogMessage: no picture source",
                        relatedId = currentSongUri
                    )
                    onResult(false)
                    return@launch
                }
                // Android 用的是 `openOutputStream(uri, "wt")`，截断重写；`File.outputStream()` 同样是截断。
                val wrotePicture = destination.outputStream().use { outputStream ->
                    writePictureSource(outputStream, source)
                }
                if (!wrotePicture) {
                    recordMetadataFailure(
                        message = "$failureLogMessage: source stream unavailable",
                        relatedId = currentSongUri,
                        detail = "Destination: ${destination.absolutePath}"
                    )
                }
                onResult(wrotePicture)
            } catch (e: Exception) {
                PlatformLog.e(TAG, "导出图片失败: ${destination.absolutePath}", e)
                recordMetadataException(
                    message = failureLogMessage,
                    relatedId = currentSongUri,
                    throwable = e
                )
                onResult(false)
            }
        }
    }

    fun clearExportCoverStatus() {
        _uiState.update { it.copy(exportCoverResult = null) }
    }

    fun clearExportArtistImageStatus() {
        _uiState.update { it.copy(exportArtistImageResult = null) }
    }

    fun revertCover() {
        _uiState.update { state ->
            val original = state.originalTagData
            if (original == null) {
                state.copy(
                    coverUri = state.originalCover,
                    editingTagData = state.editingTagData?.copy(picUrl = null)
                )
            } else {
                val displayPicture = original.pictures.frontCoverOrFallback()
                val current = state.editingTagData ?: AudioTagData()
                val originalFrontCover = original.pictures.pictureOfType(AudioPictureType.FrontCover)
                val revertedPictures = originalFrontCover?.let { picture ->
                    current.pictures
                        .removePictureType(AudioPictureType.FrontCover)
                        .replacePicture(
                            picture = picture,
                            type = AudioPictureType.FrontCover
                        )
                } ?: current.pictures.removePictureType(AudioPictureType.FrontCover)

                state.copy(
                    coverUri = state.originalCover,
                    picture = displayPicture,
                    editingTagData = state.editingTagData?.copy(
                        pictures = revertedPictures,
                        picUrl = null
                    )
                )
            }
        }
    }

    /**
     * 还原所有艺术家海报。
     *
     * 这是唯一的还原入口：逐张还原需要知道「这张图原来是哪张」，而重挂会同时删掉目标艺术家原来的
     * 海报、裁剪会改数据，光靠当前的图片内容推断不出来，猜错就是丢图或贴错别人的图。
     */
    fun revertArtistImages() {
        updateArtistPictures { pictures, original ->
            if (original == null) pictures
            else ArtistPosterEdits.revertAll(pictures, original.pictures)
        }
    }

    fun restoreCoverSnapshot(
        coverUri: Any?,
        picture: AudioPicture?,
        pictures: List<AudioPicture>,
        picUrl: String?
    ) {
        _uiState.update { state ->
            val current = state.editingTagData ?: return@update state
            state.copy(
                coverUri = coverUri,
                picture = picture,
                isEditing = true,
                editingTagData = current.copy(
                    pictures = pictures,
                    picUrl = picUrl
                )
            )
        }
    }

    fun restoreArtistImageSnapshot(pictures: List<AudioPicture>) {
        _uiState.update { state ->
            val current = state.editingTagData ?: return@update state
            state.copy(
                isEditing = true,
                editingTagData = current.copy(
                    pictures = pictures
                )
            )
        }
    }

    /**
     * 保存元数据
     */
    fun saveMetadata() {
        val state = _uiState.value
        val uriString = state.songInfo?.uriString ?: return
        val editingTagData = state.editingTagData ?: return

        if (_uiState.value.isSaving) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    saveSuccess = null,
                    saveFailureMessage = null,
                    saveFailureLogText = null
                )
            }

            try {
                val audioTagData = editingTagData.filterHiddenEditFields(
                    original = state.originalTagData,
                    visibleFieldCodes = editFieldConfigRepository.configFlow.first()
                        .visibleFieldCodesForScene(EditFieldScene.SingleEdit),
                )
                when (
                    val saveResult = overwriteSongTagsUseCase(
                        uri = uriString,
                        tagData = audioTagData,
                        // 用户基于读取到的图片列表改过图（含删空）时，必须把这份列表写下去
                        picturesAuthored = audioTagData.pictures != state.originalTagData?.pictures
                    )
                ) {
                    is SaveAudioTagsResult.Success -> {
                        val savedTagData = saveResult.tagData
                        val savedDisplayPicture = savedTagData.pictures.frontCoverOrFallback()
                        val savedDisplayCover = savedDisplayPicture?.data

                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                saveSuccess = true,
                                isEditing = false,
                                originalTagData = savedTagData,
                                editingTagData = savedTagData,
                                originalCover = savedDisplayCover,
                                coverUri = savedDisplayCover,
                                picture = savedDisplayPicture,
                            )
                        }
                        currentSong = saveResult.song
                    }

                    is SaveAudioTagsResult.Failed -> {
                        val reason = saveResult.error.localizedMessage
                            ?: saveResult.error::class.java.simpleName
                        recordSaveFailure(uriString, reason, saveResult.error)
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                saveSuccess = false,
                                saveFailureMessage = reason,
                                saveFailureLogText = buildSaveFailureLog(uriString, reason)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                PlatformLog.e(TAG, "保存元数据发生未知错误", e)
                val reason = e.localizedMessage ?: e::class.java.simpleName
                recordSaveFailure(uriString, reason, e)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveSuccess = false,
                        saveFailureMessage = reason,
                        saveFailureLogText = buildSaveFailureLog(uriString, reason, e)
                    )
                }
            }
        }
    }

    private fun AudioTagData.filterHiddenEditFields(
        original: AudioTagData?,
        visibleFieldCodes: Set<String>,
    ): AudioTagData {
        val base = original ?: return this

        fun visible(code: String): Boolean = code in visibleFieldCodes

        return copy(
            customFields = customFields.filter { field ->
                visible(EditFieldRegistry.customTagCode(field.key.uppercase(Locale.ROOT)))
            } + base.customFields.filterNot { field ->
                visible(EditFieldRegistry.customTagCode(field.key.uppercase(Locale.ROOT)))
            },
            title = if (visible("title")) title else base.title,
            artist = if (visible("artist")) artist else base.artist,
            albumArtist = if (visible("album_artist")) albumArtist else base.albumArtist,
            album = if (visible("album")) album else base.album,
            date = if (visible("date")) date else base.date,
            language = if (visible("language")) language else base.language,
            genre = if (visible("genre")) genre else base.genre,
            trackNumber = if (visible("track_number")) trackNumber else base.trackNumber,
            discNumber = if (visible("disc_number")) discNumber else base.discNumber,
            composer = if (visible("composer")) composer else base.composer,
            lyricist = if (visible("lyricist")) lyricist else base.lyricist,
            copyright = if (visible("copyright")) copyright else base.copyright,
            comment = if (visible("comment")) comment else base.comment,
            replayGainTrackGain = if (visible("track_gain")) {
                replayGainTrackGain
            } else {
                base.replayGainTrackGain
            },
            replayGainTrackPeak = if (visible("track_peak")) {
                replayGainTrackPeak
            } else {
                base.replayGainTrackPeak
            },
            replayGainAlbumGain = if (visible("album_gain")) {
                replayGainAlbumGain
            } else {
                base.replayGainAlbumGain
            },
            replayGainAlbumPeak = if (visible("album_peak")) {
                replayGainAlbumPeak
            } else {
                base.replayGainAlbumPeak
            },
            replayGainReferenceLoudness = if (visible("reference_loudness")) {
                replayGainReferenceLoudness
            } else {
                base.replayGainReferenceLoudness
            },
            lyrics = if (visible("lyrics")) lyrics else base.lyrics,
            pictures = if (visible("picture")) pictures else base.pictures,
            picUrl = if (visible("picture")) picUrl else base.picUrl,
            rating = if (visible("rating")) rating else base.rating,
        )
    }

    private suspend fun recordSaveFailure(
        uriString: String,
        reason: String,
        throwable: Throwable? = null
    ) {
        try {
            if (throwable == null) {
                appLogRepository.log(
                    level = AppLogLevel.ERROR,
                    type = AppLogType.METADATA,
                    tag = TAG,
                    message = "Failed to save metadata: $reason",
                    detail = buildSaveFailureLog(uriString, reason),
                    relatedId = uriString
                )
            } else {
                appLogRepository.log(
                    level = AppLogLevel.ERROR,
                    type = AppLogType.METADATA,
                    tag = TAG,
                    message = "Failed to save metadata: $reason",
                    detail = buildSaveFailureLog(uriString, reason, throwable),
                    relatedId = uriString
                )
            }
        } catch (logError: Exception) {
            PlatformLog.w(TAG, "Failed to write metadata save log", logError)
        }
    }

    private suspend fun recordMetadataFailure(
        message: String,
        relatedId: String? = currentSongUri,
        detail: String? = null,
        level: AppLogLevel = AppLogLevel.ERROR
    ) {
        try {
            appLogRepository.log(
                level = level,
                type = AppLogType.METADATA,
                tag = TAG,
                message = message,
                detail = detail,
                relatedId = relatedId
            )
        } catch (logError: Exception) {
            PlatformLog.w(TAG, "Failed to write metadata log", logError)
        }
    }

    private suspend fun recordMetadataException(
        message: String,
        relatedId: String? = currentSongUri,
        throwable: Throwable
    ) {
        try {
            appLogRepository.logException(
                type = AppLogType.METADATA,
                tag = TAG,
                message = message,
                throwable = throwable,
                relatedId = relatedId
            )
        } catch (logError: Exception) {
            PlatformLog.w(TAG, "Failed to write metadata exception log", logError)
        }
    }

    private fun buildSaveFailureLog(
        uriString: String,
        reason: String,
        throwable: Throwable? = null
    ): String = buildString {
        appendLine("Save metadata failed")
        appendLine("Uri: $uriString")
        appendLine("Reason: $reason")
        throwable?.let {
            appendLine("Exception: ${it::class.java.name}")
            appendLine(it.stackTraceToString())
        }
    }

    fun calculateReplayGain() {
        val uriString = currentSongUri ?: _uiState.value.songInfo?.uriString ?: return
        if (_uiState.value.isReplayGainCalculating) return

        scanJob = viewModelScope.launch {
            val replayGainSettings = settingsRepository.getReplayGainSettings()
            _uiState.update {
                it.copy(
                    isReplayGainCalculating = true,
                    replayGainCalculateProgress = 0f,
                    replayGainScanMessage = null
                )
            }

            try {
                replayGainScanner.analyze(uriString, replayGainSettings.peakMode)
                    .flowOn(Dispatchers.IO) // 将解码和 DSP 计算放入 IO 线程池，不卡顿 UI
                    .collect { state ->     // 持续监听发射出来的状态
                        when (state) {
                            is ReplayGainCalculateState.Progress -> {
                                // 实时刷新进度条
                                _uiState.update {
                                    it.copy(replayGainCalculateProgress = state.percent)
                                }
                            }

                            is ReplayGainCalculateState.Success -> {
                                // 扫描成功，写入标签
                                _uiState.update { ui ->
                                    val current = ui.editingTagData ?: AudioTagData(fileName = ui.fileName.orEmpty())
                                    ui.copy(
                                        editingTagData = current.copy(
                                            replayGainTrackGain = replayGainScanner.formatGain(
                                                state.analysis,
                                                replayGainSettings.targetLoudness
                                            ),
                                            replayGainTrackPeak = replayGainScanner.formatPeak(state.analysis.peak),
                                            replayGainReferenceLoudness = replayGainScanner.formatReferenceLoudness(
                                                replayGainSettings.targetLoudness
                                            )
                                        ),
                                        isEditing = true,
                                        isReplayGainCalculating = false,
                                        replayGainCalculateProgress = 1.0f,
                                        replayGainScanMessage = UiMessage.Localized(Res.string.replay_gain_calculate_success)
                                    )
                                }
                            }

                            is ReplayGainCalculateState.Cancelled -> {
                                // 这里处理 Flow 内部抛出异常前发出的 Cancelled 状态
                                _uiState.update {
                                    it.copy(
                                        isReplayGainCalculating = false,
                                        replayGainCalculateProgress = 0f,
                                        replayGainScanMessage = UiMessage.Localized(Res.string.replay_gain_calculate_cancelled)
                                    )
                                }
                            }

                            is ReplayGainCalculateState.Failed -> {
                                // 根据 state.error 映射具体的错误信息
                                val errorMsg = mapErrorToUiMessage(state.error)
                                recordMetadataFailure(
                                    message = "ReplayGain scan failed",
                                    relatedId = uriString,
                                    detail = buildString {
                                        appendLine("Uri: $uriString")
                                        appendLine("MimeType: ${state.mimeType ?: "unknown"}")
                                        appendLine("Error: ${state.error::class.java.name}")
                                        appendLine("Message: ${state.error.toLogMessage()}")
                                    }
                                )
                                _uiState.update {
                                    it.copy(
                                        isReplayGainCalculating = false,
                                        replayGainCalculateProgress = 0f,
                                        replayGainScanMessage = errorMsg
                                    )
                                }
                            }
                        }
                    }
            } catch (e: CancellationException) {
                // 协程被取消时，collect 可能会停止。如果是外部 job.cancel() 触发，
                // 且 state 没有发回 Cancelled，这里可以补底线逻辑
                if (_uiState.value.isReplayGainCalculating) {
                    _uiState.update {
                        it.copy(
                            isReplayGainCalculating = false,
                            replayGainScanMessage = UiMessage.Localized(Res.string.replay_gain_calculate_cancelled)
                        )
                    }
                }
            } catch (e: Exception) {
                PlatformLog.e(TAG, "扫描 ReplayGain 失败: $uriString", e)
                recordMetadataException(
                    message = "ReplayGain scan crashed",
                    relatedId = uriString,
                    throwable = e
                )
                _uiState.update {
                    it.copy(
                        isReplayGainCalculating = false,
                        replayGainCalculateProgress = 0f,
                        replayGainScanMessage = UiMessage.Localized(
                            Res.string.replay_gain_calculate_failed,
                            e.localizedMessage ?: "未知错误"
                        )
                    )
                }
            } finally {
                _uiState.update {
                    it.copy(
                        isReplayGainCalculating = false,
                        replayGainCalculateProgress = if (it.replayGainCalculateProgress == 1f) 1f else null
                    )
                }
            }
        }
    }

    /**
     * 具体的 UI 文本映射逻辑。
     *
     * 桌面端只有五种错误（见 [ReplayGainError]）：Android 的 `UnsupportedCodec` 和「ALAC 解码器
     * 有问题」这两个分支描述的都是在写标签前先问系统要解码器的过程，ffmpeg 那条路上没有对应的问题，
     * 所以它们连同 `mimeType` 参数一起删掉，解码失败一律报解码器自己给出的原因 —— 这比
     * 「这个编码不支持」信息更多。
     */
    private fun mapErrorToUiMessage(error: ReplayGainError): UiMessage {
        return when (error) {
            is ReplayGainError.NoAudioTrack -> UiMessage.Localized(Res.string.replay_gain_error_no_audio_track)
            is ReplayGainError.UnknownMimeType -> UiMessage.Localized(Res.string.replay_gain_error_unknown_mime_type)
            is ReplayGainError.ZeroSampleCount -> UiMessage.Localized(Res.string.replay_gain_error_zero_sample_count)
            is ReplayGainError.CodecException -> UiMessage.Localized(Res.string.replay_gain_error_codec_exception, error.message ?: "")
            is ReplayGainError.GeneralException -> UiMessage.Localized(Res.string.replay_gain_error_general, error.message ?: "")
        }
    }

    private fun ReplayGainError.toLogMessage(): String = when (this) {
        is ReplayGainError.NoAudioTrack -> "No audio track"
        is ReplayGainError.UnknownMimeType -> "Unknown MIME type"
        is ReplayGainError.ZeroSampleCount -> "Zero sample count"
        is ReplayGainError.CodecException -> message.orEmpty()
        is ReplayGainError.GeneralException -> message.orEmpty()
    }

    fun cancelScan() {
        scanJob?.cancel()  // 这会触发 ensureActive() 抛出 CancellationException
        scanJob = null
    }

    fun clearReplayGainScanMessage() {
        _uiState.update { it.copy(replayGainScanMessage = null) }
    }

    fun clearSaveStatus() {
        _uiState.update {
            it.copy(
                saveSuccess = null,
                saveFailureMessage = null,
                saveFailureLogText = null
            )
        }
    }

    fun clearPlayMessage() {
        _uiState.update { it.copy(playMessage = null) }
    }

    /**
     * 检测歌词格式：LRC 或 TTML
     * 返回 "lrc" 或 "ttml"
     */
    private fun getLyricsMimeType(lyrics: String?): String {
        if (lyrics.isNullOrBlank()) return "lrc"

        // 检测 TTML 格式
        if (lyrics.contains("begin=") && lyrics.contains("end=") && lyrics.contains("<?xml")) {
            return "ttml"
        }

        // 检测 LRC 格式时间戳 [mm:ss.xxx] 或 <mm:ss.xxx>
        if (Regex("[\\[<]\\d{1,2}:\\d{2}\\.\\d{2,3}[>\\]]").containsMatchIn(lyrics)) {
            return "lrc"
        }

        // 默认返回 lrc
        return "lrc"
    }

    /**
     * 获取导出歌词的默认文件名
     */
    fun getLyricsFileName(): String? {
        val lyrics = _uiState.value.editingTagData?.lyrics ?: return null
        val format = getLyricsMimeType(lyrics)
        val fileExtension = if (format == "ttml") ".ttml" else ".lrc"
        val fileName = _uiState.value.fileName ?: ""
        return "$fileName$fileExtension"
    }

    /**
     * 导出歌词到 [destination]。
     *
     * Android 往 `openOutputStream(uri)` 里写，且是在主线程上写的；桌面端换成 [File] 并挪到 IO。
     */
    fun exportLyrics(destination: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lyrics = _uiState.value.editingTagData?.lyrics
                if (lyrics.isNullOrBlank()) {
                    recordMetadataFailure(
                        message = "Failed to export lyrics: lyrics are empty",
                        relatedId = currentSongUri,
                        detail = "Destination: ${destination.absolutePath}",
                        level = AppLogLevel.WARNING
                    )
                    _uiState.update { it.copy(exportLyricsResult = false) }
                    return@launch
                }

                destination.outputStream().use { outputStream ->
                    outputStream.write(lyrics.toByteArray(Charsets.UTF_8))
                }
                _uiState.update { it.copy(exportLyricsResult = true) }
                PlatformLog.d(TAG, "歌词导出成功: ${destination.absolutePath}")
            } catch (e: Exception) {
                PlatformLog.e(TAG, "导出歌词失败", e)
                recordMetadataException(
                    message = "Failed to export lyrics",
                    relatedId = currentSongUri,
                    throwable = e
                )
                _uiState.update { it.copy(exportLyricsResult = false) }
            }
        }
    }

    private fun writePictureSource(
        outputStream: OutputStream,
        source: Any
    ): Boolean = when (getCoverSourceType(source)) {
        CoverSourceType.BYTE_ARRAY -> {
            outputStream.write(source as ByteArray)
            true
        }

        CoverSourceType.IMAGE_BITMAP -> (source as ImageBitmap).toJpegBytesOrNull()?.let { bytes ->
            outputStream.write(bytes)
            true
        } ?: false

        CoverSourceType.NETWORK_URL -> {
            // 和 Android 一样不设超时（继承 JVM 默认的无限等待）：这是「写标签/导出」的路径，
            // 半途放弃会留下半个文件。探测用的 [com.lonx.lyrico.utils.readRemoteImageSize] 另当别论。
            URL(source.toString().trim()).openStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
            true
        }

        CoverSourceType.FILE_PATH -> {
            FileInputStream(source.toString().trim()).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
            true
        }

        CoverSourceType.UNSUPPORTED -> false
    }

    fun exportLyricsToAudioDirectory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lyrics = _uiState.value.editingTagData?.lyrics
                val audioFile = currentAudioFile()
                val fileName = getLyricsFileName()
                if (lyrics.isNullOrBlank() || audioFile == null || fileName == null) {
                    recordMetadataFailure(
                        message = "Failed to export lyrics next to audio: source or lyrics unavailable",
                        relatedId = currentSongUri,
                        level = AppLogLevel.WARNING
                    )
                    _uiState.update { it.copy(exportLyricsResult = false) }
                    return@launch
                }

                val outputFile = SiblingFileWriter.write(
                    audioFile = audioFile,
                    fileName = fileName,
                    bytes = lyrics.toByteArray(Charsets.UTF_8)
                )
                _uiState.update { it.copy(exportLyricsResult = true) }
                PlatformLog.d(TAG, "歌词已导出到音频同目录: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                PlatformLog.e(TAG, "导出歌词到音频同目录失败", e)
                recordMetadataException(
                    message = "Failed to export lyrics next to audio",
                    relatedId = currentSongUri,
                    throwable = e
                )
                _uiState.update { it.copy(exportLyricsResult = false) }
            }
        }
    }

    /**
     * 导入歌词文件。
     *
     * Android 在主线程上读文件，桌面端挪到 IO（见类注释）。
     */
    fun importLyrics(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lyrics = file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }

                if (lyrics != null) {
                    updateTag { copy(lyrics = lyrics) }
                    _uiState.update { it.copy(importLyricsResult = true) }
                    PlatformLog.d(TAG, "歌词导入成功")
                } else {
                    _uiState.update { it.copy(importLyricsResult = false) }
                    PlatformLog.w(TAG, "歌词导入失败: 文件内容为空")
                    recordMetadataFailure(
                        message = "Failed to import lyrics: file content is empty",
                        relatedId = currentSongUri,
                        detail = "Source: ${file.absolutePath}",
                        level = AppLogLevel.WARNING
                    )
                }
            } catch (e: Exception) {
                PlatformLog.e(TAG, "导入歌词失败", e)
                recordMetadataException(
                    message = "Failed to import lyrics",
                    relatedId = currentSongUri,
                    throwable = e
                )
                _uiState.update { it.copy(importLyricsResult = false) }
            }
        }
    }

    fun clearExportLyricsStatus() {
        _uiState.update { it.copy(exportLyricsResult = null) }
    }

    /**
     * 对当前歌词进行简繁转换
     */
    fun convertLyrics(conversionMode: ConversionMode) {
        val currentLyrics = _uiState.value.editingTagData?.lyrics ?: return
        if (currentLyrics.isBlank()) return

        val convertedLyrics = LyricEncoder.convertLyricsText(currentLyrics, conversionMode)
        updateTag { copy(lyrics = convertedLyrics) }
    }

    /**
     * 转换歌词格式
     * @param targetFormat 目标格式
     */
    fun convertLyricsFormat(targetFormat: LyricFormat) {
        processLyrics(
            LyricsProcessingOptions(
                targetFormat = targetFormat
            )
        )
    }

    fun processLyrics(options: LyricsProcessingOptions) {
        val currentLyrics = _uiState.value.editingTagData?.lyrics ?: return
        if (currentLyrics.isBlank()) return

        viewModelScope.launch {
            try {
                if (options.formatLineOrder) {
                    val converted = withContext(Dispatchers.Default) {
                        LyricsColumnSorter.apply(currentLyrics, options.twoColumnMapping, options.threeColumnMapping)
                    }
                    if (converted != currentLyrics && _uiState.value.editingTagData?.lyrics == currentLyrics) {
                        updateTag { copy(lyrics = converted) }
                    }
                    if (options.targetFormat == null && !options.removeEmptyLines && !options.removeTagLines) return@launch
                }
                val currentFormat = LyricDecoder.detectFormat(currentLyrics)
                val targetFormat = options.targetFormat ?: currentFormat
                if (options.targetFormat != null && options.targetFormat == currentFormat && !options.hasTextOperations()) return@launch
                val tagLineKeywords = if (options.removeTagLines) {
                    settingsRepository.lyricsTagLineKeywords.first()
                } else {
                    emptyList()
                }

                val converted = if (options.targetFormat == null && !options.formatLineOrder && currentFormat != LyricFormat.TTML) {
                    LyricsTextCleanup.process(
                        raw = currentLyrics,
                        removeEmptyLines = options.removeEmptyLines,
                        tagLineKeywords = tagLineKeywords
                    )
                } else {
                    if (currentFormat == null || targetFormat == null) return@launch
                    LyricsDocumentPipeline.process(
                        raw = currentLyrics,
                        sourceFormat = currentFormat,
                        targetFormat = targetFormat,
                        removeEmptyLines = options.removeEmptyLines,
                        removeTagLineKeywords = tagLineKeywords
                    ) ?: convertLyricsFormatFromCurrent(currentLyrics, targetFormat)
                } ?: return@launch

                if (_uiState.value.editingTagData?.lyrics == currentLyrics) {
                    updateTag { copy(lyrics = converted) }
                }
            } catch (e: Exception) {
                PlatformLog.e(TAG, "歌词处理失败", e)
                recordMetadataException(
                    message = "Failed to process lyrics",
                    relatedId = currentSongUri,
                    throwable = e
                )
            }
        }
    }

    private fun convertLyricsFormatFromCurrent(
        currentLyrics: String,
        targetFormat: LyricFormat
    ): String? {
        val lyricsResult = LyricDecoder.decode(currentLyrics) ?: return null
        if (lyricsResult.original.isEmpty()) return null

        val config = LyricRenderConfig(
            format = targetFormat,
            conversionMode = ConversionMode.NONE,
            showTranslation = lyricsResult.translated != null,
            showRomanization = lyricsResult.romanization != null,
            removeEmptyLines = false,
            onlyTranslationIfAvailable = false
        )

        return LyricEncoder.encode(lyricsResult, config).takeIf { it.isNotBlank() }
    }

    fun clearImportLyricsStatus() {
        _uiState.update { it.copy(importLyricsResult = null) }
    }

    /**
     * 「播放音乐」。
     *
     * Android 拿 `Context` 发 `ACTION_VIEW` 让系统挑播放器；桌面端交给 [PlaybackRepository] 用系统
     * 关联打开（等价于在资源管理器里双击），结果按 `SongSelectionViewModel.play` 的同一套文案映射，
     * 但因为这里没有 `Toast`，消息落在 [EditMetadataUiState.playMessage] 上，由界面弹 snackbar。
     */
    fun play() {
        val pathString = currentSong?.uri ?: currentSongUri ?: return
        val path = try {
            Path.of(pathString)
        } catch (e: InvalidPathException) {
            // 路径本身不合法（Windows 保留字符等）：等同「没有可播放的文件」。
            PlatformLog.w(TAG, "无法解析歌曲路径: $pathString", e)
            _uiState.update { it.copy(playMessage = UiMessage.Localized(Res.string.no_player_found)) }
            return
        }

        when (val result = playbackRepository.open(path)) {
            PlaybackResult.Opened -> Unit
            is PlaybackResult.Failed -> _uiState.update {
                it.copy(
                    playMessage = UiMessage.Localized(
                        Res.string.unknown_error,
                        result.throwable.message.orEmpty()
                    )
                )
            }

            is PlaybackResult.FileUnavailable, PlaybackResult.Unsupported -> _uiState.update {
                it.copy(playMessage = UiMessage.Localized(Res.string.no_player_found))
            }
        }
    }

    /**
     * 获取同专辑歌曲封面
     * 优先使用同专辑且同歌手的查询结果作为封面
     */
    suspend fun getSameAlbumCovers(): List<Pair<String, Any?>> {
        val currentAlbum = _uiState.value.editingTagData?.album ?: return emptyList()
        val currentArtist = _uiState.value.editingTagData?.artist ?: ""

        val sameAlbumSongs = songLibraryRepository.getSongsByAlbum(currentAlbum, currentArtist)
        val covers = mutableListOf<Pair<String, Any?>>()

        for (song in sameAlbumSongs) {
            if (song.uri == currentSongUri) continue // 跳过当前歌曲

            try {
                val tagData = readAudioTagsUseCase(song.uri)
                val cover = tagData.pictures.frontCoverOrFallback()?.data
                if (cover != null) {
                    val title = "${song.title} - ${song.artist}"
                    covers.add(title to cover)
                }
            } catch (e: Exception) {
                PlatformLog.e(TAG, "读取同专辑歌曲封面失败: ${song.uri}", e)
                recordMetadataException(
                    message = "Failed to read same-album cover",
                    relatedId = song.uri,
                    throwable = e
                )
            }
        }

        return covers
    }

    /**
     * 加载同专辑封面（直接使用第一张）
     */
    fun loadSameAlbumCovers() {
        viewModelScope.launch {
            try {
                val covers = getSameAlbumCovers()
                if (covers.isNotEmpty()) {
                    val (_, cover) = covers.first()
                    when (cover) {
                        is String -> updateCover(cover)
                        // Android 那边 `BitmapFactory.decodeByteArray` 解不出来时返回 null，紧接着的
                        // `bitmap.toJpegBytes()` 抛 NPE 落到下面的 catch，报「加载失败」。这里保留同一
                        // 结果：解不出来就当成加载失败，而不是贴上一张空封面。
                        is ByteArray -> updateCover(checkNotNull(decodeImageBitmap(cover)))
                    }
                    _uiState.update { it.copy(sameAlbumCoverMessage = UiMessage.Localized(Res.string.msg_same_album_cover_applied)) }
                } else {
                    _uiState.update { it.copy(sameAlbumCoverMessage = UiMessage.Localized(Res.string.msg_no_same_album_cover_found)) }
                }
            } catch (e: Exception) {
                PlatformLog.e(TAG, "加载同专辑封面失败", e)
                recordMetadataException(
                    message = "Failed to load same-album cover",
                    relatedId = currentSongUri,
                    throwable = e
                )
                _uiState.update { it.copy(sameAlbumCoverMessage = UiMessage.Localized(Res.string.msg_load_same_album_cover_failed)) }
            }
        }
    }

    fun clearSameAlbumCoverMessage() {
        _uiState.update { it.copy(sameAlbumCoverMessage = null) }
    }

    fun getPlainLyrics(
        showRomanization: Boolean = true,
        showTranslation: Boolean = true
    ): String? {
        val lyricsResult = LyricDecoder.decode(_uiState.value.editingTagData?.lyrics ?: "")
            ?: return null
        if (lyricsResult.original.isEmpty()) return null
        val config = LyricRenderConfig(
            format = LyricFormat.PLAIN_LRC,
            conversionMode = ConversionMode.NONE,
            showTranslation = showTranslation && lyricsResult.translated != null,
            showRomanization = showRomanization && lyricsResult.romanization != null,
            removeEmptyLines = true,
            onlyTranslationIfAvailable = false
        )
        return LyricEncoder.encodePlainText(lyricsResult, config)
    }
}
