package com.lonx.lyrico.screens

import com.lonx.lyrico.LocalExternalAudioEditHost
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.audiotag.model.AudioTagData
import com.lonx.audiotag.model.CustomTagField
import com.lonx.audiotag.model.artistPictureTypes
import com.lonx.audiotag.model.type
import com.lonx.lyrico.data.model.lyrics.LyricsOperation
import com.lonx.lyrico.ui.components.lyrics.LyricsProcessingSheet
import com.lonx.lyrico.ui.components.lyrics.LyricsOrganizationMenu
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_add_custom_tag
import com.lonx.lyrico.resources.action_calculate_replay_gain
import com.lonx.lyrico.resources.action_cancel_undo
import com.lonx.lyrico.resources.action_delete
import com.lonx.lyrico.resources.action_export_lyrics
import com.lonx.lyrico.resources.action_import_lyrics
import com.lonx.lyrico.resources.action_lyrics_options
import com.lonx.lyrico.resources.action_main_search
import com.lonx.lyrico.resources.action_search_cover
import com.lonx.lyrico.resources.action_search_lyrics
import com.lonx.lyrico.resources.action_undo_changes
import com.lonx.lyrico.resources.action_view_plain_lyrics
import com.lonx.lyrico.resources.artist_image_untagged
import com.lonx.lyrico.resources.cancel
import com.lonx.lyrico.resources.chinese_conversion_mode_simplified_to_traditional
import com.lonx.lyrico.resources.chinese_conversion_mode_traditional_to_simplified
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.edit_artist_image
import com.lonx.lyrico.resources.edit_cover
import com.lonx.lyrico.resources.edit_field_settings_title
import com.lonx.lyrico.resources.edit_metadata_default_title
import com.lonx.lyrico.resources.ic_album_24dp
import com.lonx.lyrico.resources.ic_filled_star_24dp
import com.lonx.lyrico.resources.ic_outline_star_24dp
import com.lonx.lyrico.resources.label_artist
import com.lonx.lyrico.resources.label_artist_image
import com.lonx.lyrico.resources.label_artist_image_conflict_message
import com.lonx.lyrico.resources.label_artist_image_conflict_title
import com.lonx.lyrico.resources.label_artist_image_options
import com.lonx.lyrico.resources.label_artist_image_replace
import com.lonx.lyrico.resources.label_artist_image_swap
import com.lonx.lyrico.resources.label_change_cover
import com.lonx.lyrico.resources.label_cover
import com.lonx.lyrico.resources.label_cover_options
import com.lonx.lyrico.resources.label_crop_artist_image
import com.lonx.lyrico.resources.label_crop_cover
import com.lonx.lyrico.resources.label_custom_tag_name
import com.lonx.lyrico.resources.label_custom_tag_value
import com.lonx.lyrico.resources.label_disc_number
import com.lonx.lyrico.resources.label_image_source_embedded
import com.lonx.lyrico.resources.label_image_source_external
import com.lonx.lyrico.resources.label_lyrics
import com.lonx.lyrico.resources.label_plain_lyrics
import com.lonx.lyrico.resources.label_rating
import com.lonx.lyrico.resources.label_remove_cover
import com.lonx.lyrico.resources.label_save_cover
import com.lonx.lyrico.resources.label_select_same_album_cover
import com.lonx.lyrico.resources.label_track_number
import com.lonx.lyrico.resources.limit_lyrics_input_lines
import com.lonx.lyrico.resources.limit_lyrics_input_lines_hint
import com.lonx.lyrico.resources.lyrics_convert
import com.lonx.lyrico.resources.lyrics_empty
import com.lonx.lyrico.resources.lyrics_organize
import com.lonx.lyrico.resources.menu_play_music
import com.lonx.lyrico.resources.msg_artist_field_empty_for_poster
import com.lonx.lyrico.resources.msg_artist_image_reassigned
import com.lonx.lyrico.resources.msg_export_lyrics_failed
import com.lonx.lyrico.resources.msg_export_lyrics_success
import com.lonx.lyrico.resources.msg_field_reverted
import com.lonx.lyrico.resources.msg_import_lyrics_failed
import com.lonx.lyrico.resources.msg_import_lyrics_success
import com.lonx.lyrico.resources.msg_picture_save_failed
import com.lonx.lyrico.resources.msg_picture_saved
import com.lonx.lyrico.resources.msg_read_artist_image_failed
import com.lonx.lyrico.resources.msg_read_cover_failed
import com.lonx.lyrico.resources.msg_save_failed_with_reason
import com.lonx.lyrico.resources.msg_save_success
import com.lonx.lyrico.resources.offset_adjust_hint
import com.lonx.lyrico.resources.permission_denied_cannot_save
import com.lonx.lyrico.resources.replay_gain_calculate_in_progress
import com.lonx.lyrico.resources.roma
import com.lonx.lyrico.resources.status_modified
import com.lonx.lyrico.resources.translation
import com.lonx.lyrico.resources.unknown_error_simple
import com.lonx.lyrico.data.editfield.EditFieldDefinition
import com.lonx.lyrico.data.editfield.EditFieldKind
import com.lonx.lyrico.data.editfield.EditFieldRegistry
import com.lonx.lyrico.data.editfield.toEditFieldBlocks
import com.lonx.lyrico.data.model.ConversionMode
import com.lonx.lyrico.data.model.ExportDestination
import com.lonx.lyrico.data.model.lyrics.LyricFormat
import com.lonx.lyrico.data.model.lyrics.LyricsProcessingOptions
import com.lonx.lyrico.data.model.plugin.PluginSourceType
import com.lonx.lyrico.data.model.search.LyricsSearchResult
import com.lonx.lyrico.data.utils.ArtistNameSplitter
import com.lonx.lyrico.domain.poster.ArtistPosterGrouping
import com.lonx.lyrico.domain.poster.ArtistPosterPages
import com.lonx.lyrico.plugin.source.SearchSourceProvider
import com.lonx.lyrico.ui.components.CoverRequest
import com.lonx.lyrico.ui.components.PagerDotsIndicator
import com.lonx.lyrico.ui.components.base.LyricsOffsetField
import com.lonx.lyrico.ui.components.base.ExportDestinationBottomSheet
import com.lonx.lyrico.ui.components.blur.BlurredTopBar
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.ui.components.cover.CoverSourceImage
import com.lonx.lyrico.ui.components.cover.decodeCoverBitmap
import com.lonx.lyrico.ui.components.cover.readCoverSourceSize
import com.lonx.lyrico.ui.components.cover.rememberArtistPosterSource
import com.lonx.lyrico.ui.components.crop.ImageCropper
import com.lonx.lyrico.ui.components.crop.rememberImageCropperState
import com.lonx.lyrico.ui.components.fab.ExpandableFabMenu
import com.lonx.lyrico.ui.components.fab.ExpandableFabMenuStyle
import com.lonx.lyrico.ui.components.fab.FabMenuItem
import com.lonx.lyrico.ui.components.poster.ArtistPosterMenu
import com.lonx.lyrico.ui.components.poster.rememberArtistPosterMenuState
import com.lonx.lyrico.ui.components.rememberTintedPainter
import com.lonx.lyrico.ui.components.scaffoldTopHorizontalPadding
import com.lonx.lyrico.ui.navigation.EditFieldSettingsDestination
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.navigation.ResultRecipient
import com.lonx.lyrico.ui.navigation.SearchCoverDestination
import com.lonx.lyrico.ui.navigation.SearchLyricsDestination
import com.lonx.lyrico.ui.navigation.SearchResultsDestination
import com.lonx.lyrico.ui.navigation.onResult
import com.lonx.lyrico.ui.theme.LyricoColors
import com.lonx.lyrico.platform.rememberFileOpenPicker
import com.lonx.lyrico.platform.rememberFileSavePicker
import com.lonx.lyrico.utils.LyricDecoder
import com.lonx.lyrico.utils.formattedString
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.utils.logging.PlatformLog
import com.lonx.lyrico.viewmodel.EditMetadataViewModel
import com.lonx.lyrico.viewmodel.isEqualIgnoringBlank
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlin.apply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AddCircle
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

private const val TAG = "EditMetadataScreen"

private const val LIMITED_LYRICS_INPUT_MAX_LINES = 30

/**
 * 选本地图片时对话框过滤的扩展名。
 *
 * Android 的 `PickVisualMedia` 把「什么算图片」交给系统判断；桌面端由 `FileOpenPicker` 按文件名过滤，
 * 所以在这里列出图片格式（包含 `ImageIO` 能解码的那几种，否则选进来也读不出像素）。
 */
private val LocalImageExtensions = listOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

/** 导入歌词不过滤文件，等价于 Android 里 `OpenDocument` 传通配的 MIME 类型（见 `matchesExtensions`）。 */
private val AnyFileExtensions = listOf("*")

/** 艺术家海报描述对不上任何艺术家时，角标的警示底色。 */
private val ArtistImageUnmatchedBadgeColor = Color(0xFFB45309).copy(alpha = 0.9f)

// `ClipEntry` is @ExperimentalComposeUiApi in Compose Multiplatform; the marker sits on the screen
// rather than on the copy site, matching the other ported screens.
@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class
)
@Composable
fun EditMetadataScreen(
    navigator: Navigator,
    songFileUri: String,
    onCoverSearchResult: ResultRecipient<String>,
    onLyricsResult: ResultRecipient<LyricsSearchResult>,
    onLyricsSearchResult: ResultRecipient<LyricsSearchResult>,
    externalEditRequestId: Long? = null,
) {
    val viewModel: EditMetadataViewModel = koinViewModel()
    val searchSourceProvider: SearchSourceProvider = koinInject()
    val mainSearchSources by remember(searchSourceProvider) {
        searchSourceProvider.observeSources(PluginSourceType.METADATA)
    }.collectAsState(initial = emptyList())
    val lyricsSearchSources by remember(searchSourceProvider) {
        searchSourceProvider.observeSources(PluginSourceType.LYRICS)
    }.collectAsState(initial = emptyList())
    val coverSearchSources by remember(searchSourceProvider) {
        searchSourceProvider.observeSources(PluginSourceType.COVER)
    }.collectAsState(initial = emptyList())
    val uiState by viewModel.uiState.collectAsState()
    // 字段顺序与显隐来自「编辑字段」配置；字段块按该配置的顺序渲染。
    val visibleFields by viewModel.visibleFields.collectAsState()
    val limitLyricsInputLines by viewModel.limitLyricsInputLines.collectAsState()
    val replayGainCalculateProgress = uiState.replayGainCalculateProgress
    val originalTagData = uiState.originalTagData
    val editingTagData = uiState.editingTagData
    // 内嵌海报之外，还可以按「文件名 = 艺术家名」去外置艺术家海报文件夹里找
    val artistPosterSource = rememberArtistPosterSource()
    /**
     * 某位艺术家的**外置**海报请求：只按文件名去海报文件夹里找。
     *
     * 必须一位艺术家一个请求——外置海报是按文件名认人的，整段艺术家字段（`A/B/C`）匹配不到 `A.jpg`。
     * 也必须是 folder-only：内嵌海报的归属由 [ArtistPosterPages] / `ArtistPosterGrouping` 决定，
     * 若这里再让取图链路按自己的规则找一次内嵌图，两边对「描述为空」的旧图会给出不同答案，
     * 多艺术家时就会出现几位艺术家显示同一张图、各自的海报文件永远查不到。
     */
    fun artistPosterFileRequest(artistName: String) = CoverRequest(
        // 桌面端歌曲路径就是绝对路径本身，没有 `file://` 包装（见 `CoverRequest` 的 KDoc）
        uri = songFileUri,
        lastUpdate = 0L,
        pictureType = AudioPictureType.Artist,
        fallbackPictureTypes = listOf(AudioPictureType.LeadArtist, AudioPictureType.Band),
        // 外置海报只是内嵌艺术家海报缺失时的兜底，不要退化成普通封面
        fallbackToAny = false,
        skipEmbeddedPictures = true,
        artistName = artistName.takeIf { it.isNotBlank() },
        artistPosterFolders = listOfNotNull(artistPosterSource.folder),
        artistPosterRevision = artistPosterSource.revision
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 外部编辑请求的宿主（Android 上是 Activity）由窗口通过 CompositionLocal 提供。桌面端窗口还没有
    // 实现方，所以这里可能取到 null，见下方 `closeEditor` 与 PLAN.md 的缺口记录。
    val externalEditHost = LocalExternalAudioEditHost.current
    // 下面几段文案要在非 @Composable 的局部函数 / 协程里使用，所以在组合里先取好：
    // `msg_field_reverted`、`msg_artist_image_reassigned` 是占位符模板，用 Kotlin 的 `format` 代入。
    val cancelUndoLabel = stringResource(Res.string.action_cancel_undo)
    val fieldRevertedTemplate = stringResource(Res.string.msg_field_reverted)
    val artistImageReassignedTemplate = stringResource(Res.string.msg_artist_image_reassigned)
    val coverLabel = stringResource(Res.string.label_cover)
    val artistImageLabel = stringResource(Res.string.label_artist_image)

    fun closeEditor(saved: Boolean) {
        if (externalEditRequestId != null) {
            // Android 在这里回调 Activity，把结果交给外部调用方；桌面端没有 Activity，窗口宿主还没接入。
            // 记录而不是静默吞掉：「保存了没有」对调用方是有意义的（见 PLAN.md 的缺口记录）。
            if (externalEditHost == null) {
                PlatformLog.w(TAG, "外部编辑请求 $externalEditRequestId 没有宿主，结果无法回传（saved=$saved）")
            } else {
                externalEditHost.finishExternalAudioEdit(
                    requestId = externalEditRequestId,
                    saved = saved,
                )
            }
        } else if (!navigator.popBackStack()) {
            // Android 在这里 `activity.finish()`。桌面端「结束」意味着关掉整个窗口，而编辑页永远不是
            // 窗口的根目的地，所以只记录（见 PLAN.md 的缺口记录）。
            PlatformLog.w(TAG, "编辑页不在返回栈里，无法返回上一页")
        }
    }
    // 艺术家字段按「艺术家拆分规则」拆成一个个艺术家；艺术家海报用图片描述记录归属
    val artistSplitConfig by viewModel.artistSplitConfig.collectAsState()
    val artistNames = remember(editingTagData?.artist, artistSplitConfig) {
        ArtistNameSplitter.splitArtists(editingTagData?.artist, artistSplitConfig)
    }
    // 标签里的所有艺术家图片，以及每张图片归属的艺术家
    val artistPictureEntries = ArtistPosterGrouping.entries(
        pictures = editingTagData?.pictures.orEmpty(),
        artistNames = artistNames
    )
    val originalArtistPictures = originalTagData?.pictures.orEmpty()
        .filter { it.type in artistPictureTypes }
    // 一位艺术家一个海报位：内嵌海报优先，没有的交给外置海报文件夹
    val artistPages = ArtistPosterPages.build(
        pictures = editingTagData?.pictures.orEmpty(),
        original = originalTagData?.pictures.orEmpty(),
        artistNames = artistNames
    )
    // BottomSheet 状态
    var showOffsetSheet by remember { mutableStateOf(false) }
    var showCoverOptionsSheet by remember { mutableStateOf(false) }
    var showLyricsActionBottomSheet by remember { mutableStateOf(false) }
    var showLyricsExportDestinationSheet by remember { mutableStateOf(false) }
    var showCoverExportDestinationSheet by remember { mutableStateOf(false) }
    var showPlainLyricsSheet by remember { mutableStateOf(false) }
    var showCropSheet by remember { mutableStateOf(false) }
    var showAddCustomTagDialog by remember { mutableStateOf(false) }
    var lyricsOperation by remember { mutableStateOf(LyricsOperation.CONVERT) }
    var showLyricsProcessingSheet by remember { mutableStateOf(false) }
    var bitmapToCrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var cropRequest by remember { mutableStateOf<CropRequest>(CropRequest.Cover) }
    var isFabMenuExpanded by remember { mutableStateOf(false) }
    var isSearchFabMenuExpanded by remember { mutableStateOf(false) }
    // 艺术家海报的菜单与选择面板由组件自己管理
    val artistPosterMenu = rememberArtistPosterMenuState()
    val currentShiftOffset by viewModel.currentShiftOffset.collectAsState()

    val clipboardManager = LocalClipboard.current

    /** 弹一条带「撤销」的提示；用户点撤销时执行 [restoreChange]。 */
    fun showUndoSnackbar(message: String, restoreChange: () -> Unit) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = cancelUndoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                restoreChange()
            }
        }
    }

    fun showCancelUndoSnackbar(fieldLabel: String, restoreChange: () -> Unit) {
        showUndoSnackbar(fieldRevertedTemplate.format(fieldLabel), restoreChange)
    }

    fun <T> revertField(
        fieldLabel: String,
        currentValue: T,
        originalValue: T,
        applyValue: AudioTagData.(T) -> AudioTagData
    ) {
        viewModel.updateTag { applyValue(originalValue) }
        showCancelUndoSnackbar(fieldLabel) {
            viewModel.updateTag { applyValue(currentValue) }
        }
    }

    fun revertSimpleTextField(
        fieldLabel: String,
        field: SimpleTextField,
        original: AudioTagData?,
        edited: AudioTagData?,
    ) {
        revertField(
            fieldLabel = fieldLabel,
            currentValue = edited?.let(field.valueOf),
            originalValue = original?.let(field.valueOf),
        ) { value -> field.write(this, value) }
    }

    // 桌面端没有软键盘，也就没有「键盘弹出时收起浮动工具栏」这回事（`WindowInsets.isImeVisible` 是
    // Android 专属扩展，Compose Multiplatform 没有），所以浮动工具栏始终可见。
    val isFloatingToolbarVisible = true

    val picturePages = buildList {
        add(
            PicturePagerItem(
                label = coverLabel,
                editLabel = stringResource(Res.string.edit_cover),
                source = uiState.coverUri,
                isModified = uiState.coverUri != uiState.originalCover,
                onClick = { showCoverOptionsSheet = true },
                onRevertClick = {
                    val previousCoverUri = uiState.coverUri
                    val previousPicture = uiState.picture
                    val previousPictures = editingTagData?.pictures.orEmpty()
                    val previousPicUrl = editingTagData?.picUrl
                    viewModel.revertCover()
                    showCancelUndoSnackbar(coverLabel) {
                        viewModel.restoreCoverSnapshot(
                            coverUri = previousCoverUri,
                            picture = previousPicture,
                            pictures = previousPictures,
                            picUrl = previousPicUrl
                        )
                    }
                }
            )
        )

        if (originalTagData?.supportsTypedPictures == true) {
            // 一位艺术家一个海报位：先放标签里的内嵌海报，没有的再去外置海报文件夹按文件名找
            artistPages.forEach { page ->
                val entry = page.entry
                add(
                    PicturePagerItem(
                        label = entry?.displayName(stringResource(Res.string.artist_image_untagged))
                            ?: page.artistName.ifBlank { stringResource(Res.string.label_artist) },
                        editLabel = stringResource(Res.string.edit_artist_image),
                        source = when {
                            entry != null -> entry.picture.data
                            page.showExternalPoster -> artistPosterFileRequest(page.artistName)
                            else -> null
                        },
                        isModified = entry?.let {
                            isArtistPictureModified(it.picture, originalArtistPictures)
                        } ?: !page.showExternalPoster,
                        isUnmatched = entry?.isUnmatched == true,
                        sourceLabel = if (entry != null) {
                            stringResource(Res.string.label_image_source_embedded)
                        } else if (page.showExternalPoster) {
                            stringResource(Res.string.label_image_source_external)
                        } else {
                            null
                        },
                        onClick = { artistPosterMenu.open(entry) },
                        onRevertClick = {
                            val previousPictures = editingTagData?.pictures.orEmpty()
                            viewModel.revertArtistImages()
                            showCancelUndoSnackbar(artistImageLabel) {
                                viewModel.restoreArtistImageSnapshot(previousPictures)
                            }
                        }
                    )
                )
            }
        }
    }
    // 封面区轮播：第 0 页是封面，其后每页对应一位艺术家的海报位
    val coverPagerState = rememberPagerState(pageCount = { picturePages.size })
    val currentArtistPage = artistPages.getOrNull(coverPagerState.currentPage - 1)

    // 各种选择器。桌面端没有 SAF / ActivityResult Launcher：选文件是原生对话框，拿到的就是真正的
    // `File`，直接交给 ViewModel（它本来就只认路径/`File`）。对话框会挂起，所以在协程里调用。
    val coverImagePicker = rememberFileOpenPicker(
        title = stringResource(Res.string.edit_cover),
        extensions = LocalImageExtensions
    )
    val artistImagePicker = rememberFileOpenPicker(
        title = stringResource(Res.string.edit_artist_image),
        extensions = LocalImageExtensions
    )
    val lyricsFilePicker = rememberFileOpenPicker(
        title = stringResource(Res.string.action_import_lyrics),
        extensions = AnyFileExtensions
    )
    val lyricsFileSaver = rememberFileSavePicker(title = stringResource(Res.string.action_export_lyrics))
    val coverFileSaver = rememberFileSavePicker(title = stringResource(Res.string.label_save_cover))
    val artistImageFileSaver = rememberFileSavePicker(title = stringResource(Res.string.edit_artist_image))

    /** 选一张本地图片当封面（Android 的 `PickVisualMedia` 图片选择器）。 */
    fun pickCoverImage() {
        scope.launch { coverImagePicker.pick()?.let(viewModel::updateCover) }
    }

    /** 选一张本地图片当 [artistName] 的海报（Android 的 `PickVisualMedia` 图片选择器）。 */
    fun pickArtistImage(artistName: String) {
        scope.launch {
            val file = artistImagePicker.pick() ?: return@launch
            // 描述写入所选艺术家；该艺术家原有的海报会被替换（一个艺术家只有一张海报）
            viewModel.setArtistImage(file, artistName)
        }
    }

    fun cropArtistImage(target: AudioPicture) {
        // 仍在 IO 线程：网络来源要发请求，本地文件要读盘。桌面端解码不再需要 Context
        scope.launch(Dispatchers.IO) {
            val bitmap = decodeCoverBitmap(target.data)
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    cropRequest = CropRequest.ArtistImage(target)
                    bitmapToCrop = bitmap
                    showCropSheet = true
                } else {
                    snackbarHostState.showSnackbar(
                        formattedString(Res.string.msg_read_artist_image_failed)
                    )
                }
            }
        }
    }

    ExportDestinationBottomSheet(
        show = showLyricsExportDestinationSheet,
        onDismissRequest = { showLyricsExportDestinationSheet = false },
        onConfirm = { destination ->
            showLyricsExportDestinationSheet = false
            when (destination) {
                ExportDestination.SELECTED_DIRECTORY -> {
                    viewModel.getLyricsFileName()?.let { defaultName ->
                        scope.launch {
                            lyricsFileSaver.pick(defaultName)?.let(viewModel::exportLyrics)
                        }
                    }
                }

                ExportDestination.AUDIO_DIRECTORY -> {
                    viewModel.exportLyricsToAudioDirectory()
                }
            }
        }
    )

    ExportDestinationBottomSheet(
        show = showCoverExportDestinationSheet,
        onDismissRequest = { showCoverExportDestinationSheet = false },
        onConfirm = { destination ->
            showCoverExportDestinationSheet = false
            when (destination) {
                ExportDestination.SELECTED_DIRECTORY -> {
                    viewModel.getCoverFileName()?.let { defaultName ->
                        scope.launch {
                            coverFileSaver.pick(defaultName)?.let(viewModel::exportCover)
                        }
                    }
                }

                ExportDestination.AUDIO_DIRECTORY -> {
                    viewModel.exportCoverToAudioDirectory()
                }
            }
        }
    )

    // 事件监听
    onLyricsResult.onResult { result -> viewModel.updateMetadataFromSearchResult(result) }
    onLyricsSearchResult.onResult { result -> viewModel.updateMetadataFromSearchResult(result) }

    onCoverSearchResult.onResult { result -> viewModel.updateCover(result) }

    // Android 这里还有一个 `uiState.permissionIntentSender` 的 LaunchedEffect：往 SAF 文档里写之前要先
    // 用 IntentSender 跟用户要一次授权。桌面端写的是真实文件，没有这层授权，所以整段去掉。

    LaunchedEffect(songFileUri) { viewModel.readMetadata(songFileUri) }

    LaunchedEffect(uiState.saveSuccess) {
        uiState.saveSuccess?.let { success ->
            val message = if (success) {
                formattedString(Res.string.msg_save_success)
            } else {
                formattedString(
                    Res.string.msg_save_failed_with_reason,
                    uiState.saveFailureMessage ?: formattedString(Res.string.unknown_error_simple)
                )
            }
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = message,
                    withDismissAction = !success,
                    duration = if (success) SnackbarDuration.Short else SnackbarDuration.Indefinite
                )
            }
            viewModel.clearSaveStatus()
            if (success) {
                closeEditor(saved = true)
            }
        }
    }

    LaunchedEffect(uiState.exportLyricsResult) {
        uiState.exportLyricsResult?.let { success ->
            val msg =
                if (success) Res.string.msg_export_lyrics_success else Res.string.msg_export_lyrics_failed
            scope.launch { snackbarHostState.showSnackbar(formattedString(msg)) }
            viewModel.clearExportLyricsStatus()
        }
    }

    LaunchedEffect(uiState.exportCoverResult) {
        uiState.exportCoverResult?.let { success ->
            val message = formattedString(
                if (success) Res.string.msg_picture_saved else Res.string.msg_picture_save_failed,
                coverLabel
            )
            scope.launch { snackbarHostState.showSnackbar(message) }
            viewModel.clearExportCoverStatus()
        }
    }

    LaunchedEffect(uiState.exportArtistImageResult) {
        uiState.exportArtistImageResult?.let { success ->
            val message = formattedString(
                if (success) Res.string.msg_picture_saved else Res.string.msg_picture_save_failed,
                artistImageLabel
            )
            scope.launch { snackbarHostState.showSnackbar(message) }
            viewModel.clearExportArtistImageStatus()
        }
    }

    LaunchedEffect(uiState.importLyricsResult) {
        uiState.importLyricsResult?.let { success ->
            val msg =
                if (success) Res.string.msg_import_lyrics_success else Res.string.msg_import_lyrics_failed
            scope.launch { snackbarHostState.showSnackbar(formattedString(msg)) }
            viewModel.clearImportLyricsStatus()
        }
    }

    LaunchedEffect(uiState.replayGainScanMessage) {
        uiState.replayGainScanMessage?.let { message ->
            scope.launch {
                message.resolve()?.let { it1 -> snackbarHostState.showSnackbar(it1) }
            }
            viewModel.clearReplayGainScanMessage()
        }
    }

    LaunchedEffect(uiState.sameAlbumCoverMessage) {
        uiState.sameAlbumCoverMessage?.let { message ->
            scope.launch {
                message.resolve()?.let { it1 -> snackbarHostState.showSnackbar(it1) }
            }
            viewModel.clearSameAlbumCoverMessage()
        }
    }

    // Android 直接弹 Toast，没有这段状态；桌面端跟 `sameAlbumCoverMessage` 一样把结果留在状态里，由界面
    // 弹 snackbar（PLAN.md 的播放策略，已移除的 `PlayerPickerBottomSheet` 也走这条）。
    LaunchedEffect(uiState.playMessage) {
        uiState.playMessage?.let { message ->
            scope.launch {
                message.resolve()?.let { it1 -> snackbarHostState.showSnackbar(it1) }
            }
            viewModel.clearPlayMessage()
        }
    }

    // Android 这里有两个 BackHandler：外部编辑时按返回键等于「关闭编辑页」，以及展开的浮动菜单按返回键
    // 收起。桌面端没有系统返回键/手势，两者都不适用（见 PLAN.md 的缺口记录）。

    if (uiState.editingTagData == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val titleState = rememberMetadataTextFieldState(
        value = editingTagData?.title.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(title = it) } }
    )

    val artistState = rememberMetadataTextFieldState(
        value = editingTagData?.artist.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(artist = it) } }
    )

    val albumArtistState = rememberMetadataTextFieldState(
        value = editingTagData?.albumArtist.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(albumArtist = it) } }
    )

    val albumState = rememberMetadataTextFieldState(
        value = editingTagData?.album.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(album = it) } }
    )

    val dateState = rememberMetadataTextFieldState(
        value = editingTagData?.date.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(date = it) } }
    )

    val languageState = rememberMetadataTextFieldState(
        value = editingTagData?.language.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(language = it) } }
    )

    val genreState = rememberMetadataTextFieldState(
        value = editingTagData?.genre.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(genre = it) } }
    )

    val trackNumberState = rememberMetadataTextFieldState(
        value = editingTagData?.trackNumber.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(trackNumber = it) } }
    )

    val discNumberState = rememberMetadataTextFieldState(
        value = editingTagData?.discNumber?.toString().orEmpty(),
        onValueChange = { viewModel.updateTag { copy(discNumber = it.toIntOrNull()) } }
    )

    val composerState = rememberMetadataTextFieldState(
        value = editingTagData?.composer.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(composer = it) } }
    )

    val lyricistState = rememberMetadataTextFieldState(
        value = editingTagData?.lyricist.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(lyricist = it) } }
    )

    val copyrightState = rememberMetadataTextFieldState(
        value = editingTagData?.copyright.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(copyright = it) } }
    )

    val commentState = rememberMetadataTextFieldState(
        value = editingTagData?.comment.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(comment = it) } }
    )

    val replayGainTrackGainState = rememberMetadataTextFieldState(
        value = editingTagData?.replayGainTrackGain.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(replayGainTrackGain = it) } }
    )

    val replayGainTrackPeakState = rememberMetadataTextFieldState(
        value = editingTagData?.replayGainTrackPeak.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(replayGainTrackPeak = it) } }
    )

    val replayGainAlbumGainState = rememberMetadataTextFieldState(
        value = editingTagData?.replayGainAlbumGain.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(replayGainAlbumGain = it) } }
    )

    val replayGainAlbumPeakState = rememberMetadataTextFieldState(
        value = editingTagData?.replayGainAlbumPeak.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(replayGainAlbumPeak = it) } }
    )

    val replayGainReferenceLoudnessState = rememberMetadataTextFieldState(
        value = editingTagData?.replayGainReferenceLoudness.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(replayGainReferenceLoudness = it) } }
    )

    val lyricsState = rememberMetadataTextFieldState(
        value = editingTagData?.lyrics.orEmpty(),
        onValueChange = { viewModel.updateTag { copy(lyrics = it) } }
    )

    // 文本输入的状态与读写映射；显示顺序只由 visibleFields 决定。
    val simpleTextFields = remember(
        titleState, artistState, albumArtistState, albumState, dateState, languageState,
        genreState, composerState, lyricistState, copyrightState, commentState,
        replayGainTrackGainState, replayGainTrackPeakState, replayGainAlbumGainState,
        replayGainAlbumPeakState, replayGainReferenceLoudnessState,
    ) {
        mapOf(
            "title" to SimpleTextField(
                state = titleState,
                valueOf = { it.title },
                write = { value -> copy(title = value) },
            ),
            "artist" to SimpleTextField(
                state = artistState,
                valueOf = { it.artist },
                write = { value -> copy(artist = value) },
            ),
            "album_artist" to SimpleTextField(
                state = albumArtistState,
                valueOf = { it.albumArtist },
                write = { value -> copy(albumArtist = value) },
            ),
            "album" to SimpleTextField(
                state = albumState,
                valueOf = { it.album },
                write = { value -> copy(album = value) },
            ),
            "date" to SimpleTextField(
                state = dateState,
                valueOf = { it.date },
                write = { value -> copy(date = value) },
            ),
            "language" to SimpleTextField(
                state = languageState,
                valueOf = { it.language },
                write = { value -> copy(language = value) },
            ),
            "genre" to SimpleTextField(
                state = genreState,
                valueOf = { it.genre },
                write = { value -> copy(genre = value) },
            ),
            "composer" to SimpleTextField(
                state = composerState,
                valueOf = { it.composer },
                write = { value -> copy(composer = value) },
            ),
            "lyricist" to SimpleTextField(
                state = lyricistState,
                valueOf = { it.lyricist },
                write = { value -> copy(lyricist = value) },
            ),
            "copyright" to SimpleTextField(
                state = copyrightState,
                valueOf = { it.copyright },
                write = { value -> copy(copyright = value) },
            ),
            "comment" to SimpleTextField(
                state = commentState,
                valueOf = { it.comment },
                write = { value -> copy(comment = value) },
            ),
            "track_gain" to SimpleTextField(
                state = replayGainTrackGainState,
                valueOf = { it.replayGainTrackGain },
                write = { value -> copy(replayGainTrackGain = value) },
            ),
            "track_peak" to SimpleTextField(
                state = replayGainTrackPeakState,
                valueOf = { it.replayGainTrackPeak },
                write = { value -> copy(replayGainTrackPeak = value) },
            ),
            "album_gain" to SimpleTextField(
                state = replayGainAlbumGainState,
                valueOf = { it.replayGainAlbumGain },
                write = { value -> copy(replayGainAlbumGain = value) },
            ),
            "album_peak" to SimpleTextField(
                state = replayGainAlbumPeakState,
                valueOf = { it.replayGainAlbumPeak },
                write = { value -> copy(replayGainAlbumPeak = value) },
            ),
            "reference_loudness" to SimpleTextField(
                state = replayGainReferenceLoudnessState,
                valueOf = { it.replayGainReferenceLoudness },
                write = { value -> copy(replayGainReferenceLoudness = value) },
            ),
        )
    }

    val fieldBlocks = remember(visibleFields) { visibleFields.toEditFieldBlocks() }
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val topBarBackdrop = rememberBarBlurBackdrop()
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                val titleText = uiState.songInfo?.tagData?.title
                    ?: uiState.songInfo?.tagData?.fileName
                    ?: stringResource(Res.string.edit_metadata_default_title)

                BlurredTopBar(backdrop = topBarBackdrop) {
                    SmallTopAppBar(
                        title = titleText,
                        color = Color.Transparent,
                        defaultWindowInsetsPadding = false,
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (externalEditRequestId == null || !uiState.isSaving) {
                                        closeEditor(saved = uiState.saveSuccess == true)
                                    }
                                }
                            ) { Icon(imageVector = MiuixIcons.Back, contentDescription = null) }
                        },
                        actions = {
                            // 保存按钮
                            IconButton(
                                onClick = { viewModel.saveMetadata() },
                                enabled = !uiState.isSaving
                            ) {
                                if (uiState.isSaving) CircularProgressIndicator(
                                    modifier = Modifier.size(
                                        24.dp
                                    )
                                )
                                else Icon(imageVector = MiuixIcons.Ok, contentDescription = null)
                            }
                        },
                        scrollBehavior = topAppBarScrollBehavior
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .blurSource(topBarBackdrop)
                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                    .overScrollVertical()
                    .imePadding()
                    .scrollEndHaptic(),
                // 用 contentPadding 而不是 padding，表单才会从毛玻璃顶栏下面滚过
                contentPadding = scaffoldTopHorizontalPadding(paddingValues),
            ) {
                fieldBlocks.forEach { block ->
                    val definition = block.fields.first()
                    item(key = block.key) {
                        Column {
                            if (block.kind == EditFieldKind.ReplayGain) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 4.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = uiState.isReplayGainCalculating,
                                            enter = fadeIn(),
                                            exit = fadeOut()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                androidx.compose.material3.CircularProgressIndicator(
                                                    progress = { replayGainCalculateProgress ?: 0f },
                                                    modifier = Modifier.size(20.dp),
                                                    color = MiuixTheme.colorScheme.primary,
                                                    strokeWidth = 2.5.dp,
                                                    trackColor = MiuixTheme.colorScheme.primary.copy(
                                                        alpha = 0.2f
                                                    )
                                                )

                                                Spacer(modifier = Modifier.width(8.dp))

                                                Text(
                                                    text = "${((replayGainCalculateProgress ?: 0f) * 100).toInt()}%",
                                                    fontSize = 12.sp,
                                                    color = MiuixTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(MiuixTheme.colorScheme.primary)
                                            .clickable {
                                                if (!uiState.isReplayGainCalculating) {
                                                    viewModel.calculateReplayGain()
                                                } else {
                                                    viewModel.cancelScan()
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (uiState.isReplayGainCalculating) {
                                                stringResource(Res.string.replay_gain_calculate_in_progress)
                                            } else {
                                                stringResource(Res.string.action_calculate_replay_gain)
                                            },
                                            fontSize = 11.sp,
                                            color = MiuixTheme.colorScheme.onPrimary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            when {
                                definition.custom -> {
                                    val key = requireNotNull(EditFieldRegistry.customTagKeyOf(definition.code))
                                    val field = editingTagData?.customFields
                                        .orEmpty()
                                        .firstOrNull { it.key.equals(key, ignoreCase = true) }
                                        ?.copy(key = key)
                                        ?: CustomTagField(
                                            key = key,
                                            value = "",
                                        )
                                    val originalField = originalTagData?.customFields
                                        .orEmpty()
                                        .firstOrNull { it.key.equals(key, ignoreCase = true) }
                                        ?.copy(key = key)
                                    CustomMetadataFieldEditor(
                                        field = field,
                                        isModified = originalField
                                            ?.let { field != it }
                                            ?: field.value.isNotEmpty(),
                                        onValueChange = { newValue ->
                                            viewModel.updateCustomFieldValue(key, newValue)
                                        },
                                        onRemove = {
                                            viewModel.removeCustomFieldValue(key)
                                        },
                                        onRevert = {
                                            val previousField = editingTagData?.customFields
                                                .orEmpty()
                                                .firstOrNull { it.key.equals(key, ignoreCase = true) }
                                                ?.copy(key = key)
                                            viewModel.revertCustomField(key)
                                            showCancelUndoSnackbar(key) {
                                                if (previousField != null) {
                                                    viewModel.updateCustomFieldValue(
                                                        key = key,
                                                        value = previousField.value
                                                    )
                                                } else {
                                                    viewModel.removeCustomFieldValue(key)
                                                }
                                            }
                                        }
                                    )
                                }
                                block.kind == EditFieldKind.Cover -> {
                                    CoverSection(
                                        coverUri = uiState.coverUri,
                                        title = editingTagData?.title
                                            ?: uiState.songInfo?.tagData?.fileName?.substringBeforeLast(".")
                                            ?: "",
                                        artist = editingTagData?.artist ?: "",
                                        pages = picturePages,
                                        pagerState = coverPagerState,
                                    )
                                }
                                definition.code == "rating" -> {
                                    RatingField(
                                        rating = editingTagData?.rating,
                                        onRatingChange = { value -> viewModel.updateTag { copy(rating = value) } },
                                    )
                                }
                                definition.code == "lyrics" -> {
                                    // 撤销提示要在非 @Composable 的 lambda 里拼，先在组合里取好
                                    val lyricsLabel = stringResource(Res.string.label_lyrics)
                                    MetadataInputField(
                                        label = lyricsLabel,
                                        state = lyricsState,
                                        isModified = !editingTagData?.lyrics.isEqualIgnoringBlank(
                                            originalTagData?.lyrics
                                        ),
                                        onRevert = {
                                            revertField(
                                                fieldLabel = lyricsLabel,
                                                currentValue = editingTagData?.lyrics ?: "",
                                                originalValue = originalTagData?.lyrics ?: ""
                                            ) { copy(lyrics = it) }
                                        },
                                        actionButtons = {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(
                                                    6.dp,
                                                    Alignment.End
                                                )
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(CircleShape)
                                                        .background(MiuixTheme.colorScheme.primary)
                                                        .clickable {
                                                            showLyricsActionBottomSheet = true
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = stringResource(Res.string.action_lyrics_options),
                                                        fontSize = 11.sp,
                                                        color = MiuixTheme.colorScheme.onPrimary,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                        },
                                        isMultiline = true,
                                        limitMultilineLines = limitLyricsInputLines
                                    )
                                }
                                definition.code == "track_number" -> {
                                    val trackNumberLabel = stringResource(Res.string.label_track_number)
                                    MetadataInputField(
                                        label = trackNumberLabel,
                                        state = trackNumberState,
                                        isModified = !editingTagData?.trackNumber.isEqualIgnoringBlank(
                                            originalTagData?.trackNumber
                                        ),
                                        onRevert = {
                                            revertField(
                                                fieldLabel = trackNumberLabel,
                                                currentValue = editingTagData?.trackNumber ?: "",
                                                originalValue = originalTagData?.trackNumber ?: ""
                                            ) { copy(trackNumber = it) }
                                        }
                                    )
                                }
                                definition.code == "disc_number" -> {
                                    val discNumberLabel = stringResource(Res.string.label_disc_number)
                                    MetadataInputField(
                                        label = discNumberLabel,
                                        state = discNumberState,
                                        isModified = editingTagData?.discNumber != originalTagData?.discNumber,
                                        onRevert = {
                                            revertField(
                                                fieldLabel = discNumberLabel,
                                                currentValue = editingTagData?.discNumber,
                                                originalValue = originalTagData?.discNumber
                                            ) { copy(discNumber = it) }
                                        }
                                    )
                                }
                                definition.simpleTextInput -> {
                                    block.fields.forEach { textDefinition ->
                                        val field = simpleTextFields.getValue(textDefinition.code)
                                        // 同上：撤销提示要在非 @Composable 的 lambda 里拼
                                        val fieldLabel = stringResource(textDefinition.titleRes)
                                        MetadataInputField(
                                            label = fieldLabel,
                                            state = field.state,
                                            isModified = !editedValue(editingTagData, field)
                                                .isEqualIgnoringBlank(editedValue(originalTagData, field)),
                                            onRevert = {
                                                revertSimpleTextField(fieldLabel, field, originalTagData, editingTagData)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }
        val fabMenuItemCount = remember(editingTagData) {
            var count = 3 // 固定项：添加自定义标签、播放、字段显示设置

            if (editingTagData != null) {
                count += 3 // 歌词操作、封面操作、艺术家图片操作
            }

            count
        }
        val searchMenuItemCount = remember(mainSearchSources, lyricsSearchSources, coverSearchSources) {
            listOf(mainSearchSources, lyricsSearchSources, coverSearchSources).count { it.isNotEmpty() }
        }
        if (searchMenuItemCount > 0) {
            ExpandableFabMenu(
                modifier = Modifier.padding(bottom = 116.dp),
                visible = isFloatingToolbarVisible,
                expanded = isSearchFabMenuExpanded,
                enabled = true,
                itemCount = searchMenuItemCount,
                style = ExpandableFabMenuStyle.default().copy(mainIcon = MiuixIcons.Search),
                onExpandedChange = { expanded ->
                    if (expanded) isFabMenuExpanded = false
                    isSearchFabMenuExpanded = expanded
                }
            ) {
                if (mainSearchSources.isNotEmpty()) {
                    FabMenuItem(
                        label = stringResource(Res.string.action_main_search),
                        icon = MiuixIcons.Search,
                        onClick = {
                            isSearchFabMenuExpanded = false
                            val keyword = if (!editingTagData?.title.isNullOrEmpty()) {
                                if (editingTagData.artist.isNullOrEmpty()) editingTagData.title!!
                                else "${editingTagData.title} ${editingTagData.artist}"
                            } else {
                                uiState.songInfo?.tagData?.fileName?.substringBeforeLast(".") ?: ""
                            }
                            navigator.navigate(SearchResultsDestination(keyword))
                        }
                    )
                }
                if (lyricsSearchSources.isNotEmpty()) {
                    FabMenuItem(
                        label = stringResource(Res.string.action_search_lyrics),
                        icon = MiuixIcons.Notes,
                        onClick = {
                            isSearchFabMenuExpanded = false
                            navigator.navigate(
                                SearchLyricsDestination(
                                    title = editingTagData?.title.orEmpty(),
                                    artist = editingTagData?.artist.orEmpty(),
                                    album = editingTagData?.album.orEmpty(),
                                    date = editingTagData?.date.orEmpty()
                                )
                            )
                        }
                    )
                }
                if (coverSearchSources.isNotEmpty()) {
                    FabMenuItem(
                        label = stringResource(Res.string.action_search_cover),
                        icon = MiuixIcons.Image,
                        onClick = {
                            isSearchFabMenuExpanded = false
                            val keyword = if (!editingTagData?.title.isNullOrEmpty()) {
                                if (editingTagData.artist.isNullOrEmpty()) editingTagData.title!!
                                else "${editingTagData.title} ${editingTagData.artist}"
                            } else {
                                uiState.songInfo?.tagData?.fileName?.substringBeforeLast(".") ?: ""
                            }
                            navigator.navigate(SearchCoverDestination(keyword))
                        }
                    )
                }
            }
        }
        ExpandableFabMenu(
            modifier = Modifier
                .padding(bottom = 48.dp),
            visible = isFloatingToolbarVisible,
            expanded = isFabMenuExpanded,
            enabled = true,
            itemCount = fabMenuItemCount,
            onExpandedChange = { expanded ->
                if (expanded) isSearchFabMenuExpanded = false
                isFabMenuExpanded = expanded
            }
        ) {
            FabMenuItem(
                label = stringResource(Res.string.action_add_custom_tag),
                icon = MiuixIcons.AddCircle,
                onClick = {
                    isFabMenuExpanded = false
                    showAddCustomTagDialog = true
                }
            )

            if (editingTagData != null) {
                FabMenuItem(
                    label = stringResource(Res.string.action_lyrics_options),
                    icon = MiuixIcons.Notes,
                    onClick = {
                        isFabMenuExpanded = false
                        showLyricsActionBottomSheet = true
                    }
                )
            }

            if (editingTagData != null) {
                FabMenuItem(
                    label = stringResource(Res.string.label_cover_options),
                    icon = MiuixIcons.Image,
                    onClick = {
                        isFabMenuExpanded = false
                        showCoverOptionsSheet = true
                    }
                )
                FabMenuItem(
                    label = stringResource(Res.string.label_artist_image_options),
                    icon = MiuixIcons.Image,
                    onClick = {
                        isFabMenuExpanded = false
                        // 从菜单进来时没有指定海报，多张时需要用户自己选
                        artistPosterMenu.open()
                    }
                )
            }

            FabMenuItem(
                label = stringResource(Res.string.menu_play_music),
                icon = MiuixIcons.Play,
                onClick = {
                    isFabMenuExpanded = false
                    viewModel.play()
                }
            )

            FabMenuItem(
                label = stringResource(Res.string.edit_field_settings_title),
                icon = MiuixIcons.Settings,
                onClick = {
                    isFabMenuExpanded = false
                    // 无参目的地是 data object，按对象本身导航（见 `LocalSearchDestination` 的用法）
                    navigator.navigate(EditFieldSettingsDestination)
                }
            )
        }
    }
    // Android 这里弹 `PlayerPickerBottomSheet` 让用户选一个播放器（Activity 的 chooser）；桌面端按
    // PLAN.md 的播放策略交给 `PlaybackRepository`（系统默认关联程序），结果落在 `uiState.playMessage`。
    // 歌词操作
    WindowBottomSheet(
        show = showLyricsActionBottomSheet,
        enableNestedScroll = false,
        title = stringResource(Res.string.action_lyrics_options),
        onDismissRequest = { showLyricsActionBottomSheet = false }
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.secondaryContainer,
                )
            ) {
                if (lyricsSearchSources.isNotEmpty()) {
                    ArrowPreference(
                        title = stringResource(Res.string.action_search_lyrics),
                        onClick = {
                            showLyricsActionBottomSheet = false
                            navigator.navigate(
                                SearchLyricsDestination(
                                    title = editingTagData?.title.orEmpty(),
                                    artist = editingTagData?.artist.orEmpty(),
                                    album = editingTagData?.album.orEmpty(),
                                    date = editingTagData?.date.orEmpty()
                                )
                            )
                        }
                    )
                }
                ArrowPreference(
                    title = stringResource(Res.string.action_import_lyrics),
                    onClick = {
                        showLyricsActionBottomSheet = false
                        scope.launch {
                            lyricsFilePicker.pick()?.let(viewModel::importLyrics)
                        }
                    }
                )
                editingTagData?.lyrics?.let {
                    ArrowPreference(
                        title = stringResource(Res.string.action_export_lyrics),
                        onClick = {
                            showLyricsActionBottomSheet = false
                            showLyricsExportDestinationSheet = true
                        }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.chinese_conversion_mode_simplified_to_traditional),
                        onClick = {
                            showLyricsActionBottomSheet = false
                            viewModel.convertLyrics(ConversionMode.SIMPLIFIED_TO_TRADITIONAL)
                        }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.chinese_conversion_mode_traditional_to_simplified),
                        onClick = {
                            showLyricsActionBottomSheet = false
                            viewModel.convertLyrics(ConversionMode.TRADITIONAL_TO_SIMPLIFIED)
                        }
                    )

                    ArrowPreference(
                        title = stringResource(Res.string.offset_adjust_hint),
                        onClick = {
                            showLyricsActionBottomSheet = false
                            viewModel.prepareLyricsOffset()
                            showOffsetSheet = true
                        }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.lyrics_convert),
                        onClick = {
                            showLyricsActionBottomSheet = false
                            lyricsOperation = LyricsOperation.CONVERT
                            showLyricsProcessingSheet = true
                        }
                    )
                    LyricsOrganizationMenu(onSelect = { operation ->
                        showLyricsActionBottomSheet = false
                        lyricsOperation = operation
                        showLyricsProcessingSheet = true
                    }) { open ->
                        ArrowPreference(title = stringResource(Res.string.lyrics_organize), onClick = open)
                    }
                    ArrowPreference(
                        title = stringResource(Res.string.action_view_plain_lyrics),
                        onClick = {
                            showLyricsActionBottomSheet = false
                            showPlainLyricsSheet = true
                        }
                    )
                }
                SwitchPreference(
                    title = stringResource(Res.string.limit_lyrics_input_lines),
                    summary = stringResource(Res.string.limit_lyrics_input_lines_hint),
                    checked = limitLyricsInputLines,
                    onCheckedChange = { enabled ->
                        viewModel.setLimitLyricsInputLines(enabled)
                    }
                )
            }
        }
    }
    // 歌词文本预览
    var plainLyricsShowRomanization by remember { mutableStateOf(true) }
    var plainLyricsShowTranslation by remember { mutableStateOf(true) }
    val plainLyrics = viewModel.getPlainLyrics(
        showRomanization = plainLyricsShowRomanization,
        showTranslation = plainLyricsShowTranslation
    )
    WindowBottomSheet(
        show = showPlainLyricsSheet,
        enableNestedScroll = false,
        endAction = {
            IconButton(
                onClick = {
                    showPlainLyricsSheet = false
                    if (!plainLyrics.isNullOrEmpty()) {
                        scope.launch {
                            // 桌面端没有 Android 的 `ClipData`；`ClipEntry` 直接包一段纯文本
                            clipboardManager.setClipEntry(ClipEntry(StringSelection(plainLyrics)))
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = null
                )
            }
        },
        title = stringResource(Res.string.label_plain_lyrics),
        onDismissRequest = { showPlainLyricsSheet = false }
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 32.dp)
                .fillMaxWidth(),
        ) {
            val plainLyricsScrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .heightIn(min = 30.dp, max = 420.dp)
                    .fillMaxWidth()
                    .verticalScroll(plainLyricsScrollState)
            ) {
                SelectionContainer(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        style = MiuixTheme.textStyles.body2,
                        text = plainLyrics ?: stringResource(Res.string.lyrics_empty),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlainLyricsToggleChip(
                    text = stringResource(Res.string.roma),
                    selected = plainLyricsShowRomanization,
                    onClick = { plainLyricsShowRomanization = !plainLyricsShowRomanization }
                )
                PlainLyricsToggleChip(
                    text = stringResource(Res.string.translation),
                    selected = plainLyricsShowTranslation,
                    onClick = { plainLyricsShowTranslation = !plainLyricsShowTranslation }
                )
            }
        }
    }
    // 封面操作
    WindowBottomSheet(
        show = showCoverOptionsSheet,
        enableNestedScroll = false,
        title = stringResource(Res.string.label_cover_options),
        onDismissRequest = { showCoverOptionsSheet = false }
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Card(
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
            ) {
                if (coverSearchSources.isNotEmpty()) {
                    ArrowPreference(
                        title = stringResource(Res.string.action_search_cover),
                        onClick = {
                            val keyword = if (!editingTagData?.title.isNullOrEmpty()) {
                                if (editingTagData.artist.isNullOrEmpty()) editingTagData.title!!
                                else "${editingTagData.title} ${editingTagData.artist}"
                            } else {
                                uiState.songInfo?.tagData?.fileName?.substringBeforeLast(".") ?: ""
                            }
                            showCoverOptionsSheet = false
                            navigator.navigate(SearchCoverDestination(keyword))
                        }
                    )
                }
                ArrowPreference(
                    title = stringResource(Res.string.label_change_cover),
                    onClick = {
                        showCoverOptionsSheet = false
                        pickCoverImage()
                    }
                )
                ArrowPreference(
                    title = stringResource(Res.string.label_select_same_album_cover),
                    onClick = {
                        showCoverOptionsSheet = false
                        viewModel.loadSameAlbumCovers()
                    }
                )
                ArrowPreference(
                    title = stringResource(Res.string.label_remove_cover),
                    onClick = {
                        showCoverOptionsSheet = false
                        viewModel.removeFrontCover()
                    }
                )
                if (uiState.coverUri != null || uiState.originalCover != null) {
                    ArrowPreference(
                        title = stringResource(Res.string.label_save_cover),
                        onClick = {
                            showCoverOptionsSheet = false
                            showCoverExportDestinationSheet = true
                        }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.label_crop_cover),
                        onClick = {
                            showCoverOptionsSheet = false
                            val sourceData = uiState.coverUri ?: uiState.originalCover

                            if (sourceData != null) {
                                scope.launch(Dispatchers.IO) {
                                    val bitmap = decodeCoverBitmap(sourceData)
                                    withContext(Dispatchers.Main) {
                                        if (bitmap != null) {
                                            cropRequest = CropRequest.Cover
                                            bitmapToCrop = bitmap
                                            showCropSheet = true
                                        } else {
                                            // “无法读取封面图片”
                                            snackbarHostState.showSnackbar(
                                                formattedString(Res.string.msg_read_cover_failed)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }

        }
    }

    /** 重新绑定：目标艺术家已有海报时，等用户选「交换归属」还是「直接替换」。 */
    var pendingRebind by remember { mutableStateOf<PendingRebind?>(null) }

    /** 改挂归属，给一次反悔机会。 */
    fun applyRebind(target: AudioPicture, artistName: String, swap: Boolean = false) {
        val previousPictures = editingTagData?.pictures.orEmpty()
        if (swap) {
            viewModel.swapArtistImages(target, artistName)
        } else {
            viewModel.reassignArtistImages(target, artistName)
        }
        showUndoSnackbar(
            artistImageReassignedTemplate.format(artistName)
        ) {
            viewModel.restoreArtistImageSnapshot(previousPictures)
        }
    }

    // 艺术家海报的操作菜单与选择面板
    ArtistPosterMenu(
        state = artistPosterMenu,
        artists = artistNames,
        entries = artistPictureEntries,
        currentEntry = currentArtistPage?.entry,
        currentPageIsArtistSlot = currentArtistPage != null,
        onSetPoster = { pickArtistImage(it) },
        onRemove = { viewModel.removeArtistImage(it) },
        onExport = { target ->
            // Android 是 `exportArtistImage(context, target)`，内部再弹 CreateDocument；桌面端先把保存路径
            // 拿到手，再交给 ViewModel
            scope.launch {
                val defaultName = viewModel.getArtistImageFileName()
                artistImageFileSaver.pick(defaultName)?.let { file ->
                    viewModel.exportArtistImage(target, file)
                }
            }
        },
        onCrop = { cropArtistImage(it) },
        onReassign = { target, artistName ->
            val ownerKey = ArtistPosterGrouping.ownerKeyOf(artistName)
            val occupied = artistPictureEntries.any {
                it.picture !== target && it.artist != null && it.ownerKey == ownerKey
            }
            if (occupied) {
                // 目标艺术家已有海报：让用户决定是交换归属还是直接替换掉它
                pendingRebind = PendingRebind(target, artistName)
            } else {
                applyRebind(target, artistName)
            }
        },
        onMissingArtist = {
            scope.launch {
                snackbarHostState.showSnackbar(
                    formattedString(Res.string.msg_artist_field_empty_for_poster)
                )
            }
        },
    )
    // 裁剪界面
    val cropperState = bitmapToCrop?.let { rememberImageCropperState(it) }

    WindowBottomSheet(
        show = showCropSheet,
        enableNestedScroll = false,
        title = stringResource(
            if (cropRequest is CropRequest.ArtistImage) Res.string.label_crop_artist_image
            else Res.string.label_crop_cover
        ),
        endAction = {
            if (cropperState != null) {
                IconButton(
                    onClick = {
                        val croppedBitmap = cropperState.crop()
                        when (val request = cropRequest) {
                            CropRequest.Cover -> viewModel.updateCover(croppedBitmap)
                            is CropRequest.ArtistImage ->
                                viewModel.replaceArtistImage(croppedBitmap, request.target)
                        }
                        showCropSheet = false
                        // 注意：这里不清空 bitmapToCrop，等动画结束再清
                    }
                ) {
                    Icon(
                        imageVector = MiuixIcons.Ok,
                        contentDescription = null
                    )
                }
            }
        },
        onDismissRequest = {
            showCropSheet = false
            // 同样不在这里清空
        },
        onDismissFinished = {
            // 动画完全结束后再清理，避免闪烁
            bitmapToCrop = null
        }
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 32.dp)
                .fillMaxWidth()
        ) {
            if (cropperState != null) {
                ImageCropper(
                    state = cropperState,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
    // 偏移调整 BottomSheet
    WindowBottomSheet(
        show = showOffsetSheet,
        enableNestedScroll = false,
        title = stringResource(Res.string.offset_adjust_hint),
        onDismissRequest = { showOffsetSheet = false }
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .padding(horizontal = 8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = editingTagData?.lyrics ?: "",
                        style = MiuixTheme.textStyles.footnote1
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Card(
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                LyricsOffsetField(
                    offset = currentShiftOffset,
                    onOffsetChange = { viewModel.applyLyricsOffset(it) },
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }
    }
    // 添加自定义标签 dialog
    // 目标艺术家已有海报：交换归属，还是把原来那张替换掉
    pendingRebind?.let { pending ->
        WindowDialog(
            show = true,
            title = formattedStringResource(Res.string.label_artist_image_conflict_title, pending.artistName),
            onDismissRequest = { pendingRebind = null }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(Res.string.label_artist_image_conflict_message),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = stringResource(Res.string.label_artist_image_swap),
                        onClick = {
                            pendingRebind = null
                            applyRebind(pending.target, pending.artistName, swap = true)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(Res.string.label_artist_image_replace),
                        onClick = {
                            pendingRebind = null
                            applyRebind(pending.target, pending.artistName)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    WindowDialog(
        show = showAddCustomTagDialog,
        title = stringResource(Res.string.action_add_custom_tag),
        onDismissRequest = { showAddCustomTagDialog = false }
    ) {
        val newCustomTagKeyState = rememberTextFieldState()
        val newCustomTagValueState = rememberTextFieldState()

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                state = newCustomTagKeyState,
                label = stringResource(Res.string.label_custom_tag_name),
                modifier = Modifier.fillMaxWidth(),
                lineLimits = TextFieldLineLimits.SingleLine
            )

            Spacer(modifier = Modifier.height(12.dp))

            TextField(
                state = newCustomTagValueState,
                label = stringResource(Res.string.label_custom_tag_value),
                modifier = Modifier.fillMaxWidth(),
                lineLimits = TextFieldLineLimits.SingleLine
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = {
                        showAddCustomTagDialog = false
                    },
                    modifier = Modifier.weight(1f),
                )

                Spacer(Modifier.width(20.dp))

                TextButton(
                    text = stringResource(Res.string.confirm),
                    onClick = {
                        val key = newCustomTagKeyState.text.toString()
                        val value = newCustomTagValueState.text.toString()

                        if (key.isNotBlank()) {
                            viewModel.addCustomFieldAndShow(
                                key = key,
                                value = value,
                            )
                            showAddCustomTagDialog = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
    LyricsProcessingSheet(
        show = showLyricsProcessingSheet,
        raw = editingTagData?.lyrics.orEmpty(),
        operation = lyricsOperation,
        onDismiss = { showLyricsProcessingSheet = false },
        onConfirm = { options ->
            viewModel.processLyrics(options)
            showLyricsProcessingSheet = false
        }
    )

}

@Composable
private fun PlainLyricsToggleChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (selected) {
        MiuixTheme.colorScheme.onPrimary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    }

    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 等待用户决定「交换归属」还是「直接替换」的一次重新绑定。 */
private data class PendingRebind(val target: AudioPicture, val artistName: String)

/** 当前正在裁剪的目标。 */
private sealed interface CropRequest {
    data object Cover : CropRequest

    /** 只替换这一张艺术家图片的数据，保留它的归属。 */
    data class ArtistImage(val target: AudioPicture) : CropRequest
}

/** 这张艺术家海报和原始标签里的同名海报是否已经不同（新增或数据/描述被改过）。 */
private fun isArtistPictureModified(
    picture: AudioPicture,
    originals: List<AudioPicture>
): Boolean = originals.none {
    it.type == picture.type &&
        it.description == picture.description &&
        it.data.contentEquals(picture.data)
}

private data class PicturePagerItem(
    val label: String,
    val editLabel: String,
    val source: Any?,
    val isModified: Boolean,
    /** 描述对不上任何已有艺术家，标签会用警示色提示。 */
    val isUnmatched: Boolean = false,
    /** 图片来源说明（内嵌在标签里 / 来自外置海报文件夹），显示在尺寸旁边。 */
    val sourceLabel: String? = null,
    val onClick: () -> Unit,
    val onRevertClick: () -> Unit
)

@Composable
private fun CoverSection(
    coverUri: Any?,
    title: String,
    artist: String,
    pages: List<PicturePagerItem>,
    pagerState: PagerState,
) {
    val surfaceVariant = MiuixTheme.colorScheme.surfaceVariant
    val onSurface = MiuixTheme.colorScheme.onSurface
    val onSurfaceDim = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val picturePages = pages
    val pagerScope = rememberCoroutineScope()
    val currentPage = pagerState.currentPage.coerceIn(0, picturePages.lastIndex)
    val currentImageSource = picturePages[currentPage].source
    var imageSize by remember(currentImageSource) { mutableStateOf<Pair<Int, Int>?>(null) }

    // 加载图片尺寸。桌面端不再需要 Context：字节/路径/URL/位图/外置海报文件都由 `readCoverSourceSize`
    // 自己分辨并读取（内部就派发到 IO）。也不必再手写 `BitmapFactory` + `inJustDecodeBounds` —— 本地文件
    // 只读文件头，网络只读响应头。
    LaunchedEffect(currentImageSource) {
        imageSize = currentImageSource?.let { readCoverSourceSize(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {

                CoverSourceImage(
                    source = currentImageSource ?: coverUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = 0.15f }
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    surfaceVariant.copy(alpha = 0.6f),
                                    surfaceVariant.copy(alpha = 0.95f)
                                )
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .size(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MiuixTheme.colorScheme.onSurfaceContainerVariant)
                        ) { page ->
                            val item = picturePages[page]
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { item.onClick() }
                            ) {
                                CoverSourceImage(
                                    source = item.source,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize(),
                                    placeholder = rememberTintedPainter(
                                        painter = painterResource(Res.drawable.ic_album_24dp),
                                        tint = LyricoColors.coverPlaceholderIcon
                                    ),
                                    error = rememberTintedPainter(
                                        painter = painterResource(Res.drawable.ic_album_24dp),
                                        tint = LyricoColors.coverPlaceholderIcon
                                    )
                                )

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                        .background(
                                            // 对不上艺术家的海报用警示色提示需要重新指定归属
                                            color = if (item.isUnmatched) {
                                                ArtistImageUnmatchedBadgeColor
                                            } else {
                                                Color.Black.copy(alpha = 0.6f)
                                            },
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = item.label,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (page == currentPage) {
                                    // 尺寸 + 图片来源（内嵌在标签里 / 来自外置海报文件夹）
                                    val info = listOfNotNull(
                                        imageSize?.let { "${it.first}×${it.second}" },
                                        item.sourceLabel
                                    ).joinToString(" · ")
                                    if (info.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(8.dp)
                                                .background(
                                                    color = Color.Black.copy(alpha = 0.6f),
                                                    shape = RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = info,
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .background(
                                            color = Color.Black.copy(alpha = 0.6f),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = item.editLabel,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                androidx.compose.animation.AnimatedVisibility(
                                    visible = item.isModified,
                                    enter = scaleIn() + fadeIn(),
                                    exit = scaleOut() + fadeOut(),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(
                                                LyricoColors.modifiedBadgeBackground.copy(alpha = 0.95f)
                                            )
                                            .clickable { item.onRevertClick() }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.action_undo_changes),
                                            fontSize = 10.sp,
                                            color = LyricoColors.modifiedText,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                        if (picturePages.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            PagerDotsIndicator(
                                pageCount = picturePages.size,
                                currentPage = currentPage,
                                pageDescriptions = picturePages.map { it.label },
                                onPageClick = { target ->
                                    pagerScope.launch { pagerState.animateScrollToPage(target) }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = title.ifEmpty { "未知曲目" },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (artist.isNotEmpty()) {
                            Text(
                                text = artist,
                                fontSize = 13.sp,
                                color = onSurfaceDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))


                    }
                }
            }
        }
    }
}




@Composable
private fun RatingField(rating: Int?, onRatingChange: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(Res.string.label_rating))
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 1..5) {
                    val isFilled = rating?.let { i <= it }
                    Icon(
                        painter = painterResource(
                            if (isFilled == true) Res.drawable.ic_filled_star_24dp
                            else Res.drawable.ic_outline_star_24dp
                        ),
                        contentDescription = null,
                        tint = if (isFilled == true)
                            MiuixTheme.colorScheme.primary
                        else
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onRatingChange(
                                    if (rating == i) 0 else i
                                )
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataInputField(
    label: String,
    state: TextFieldState,
    modifier: Modifier = Modifier,
    isModified: Boolean = false,
    onRevert: () -> Unit,
    isMultiline: Boolean = false,
    limitMultilineLines: Boolean = false,
    actionButtons: @Composable RowScope.() -> Unit = {}
) {
    val fieldLabel = label + if (isModified) {
        "(" + stringResource(Res.string.status_modified) + ")"
    } else {
        ""
    }

    val colors = TextFieldDefaults.textFieldColors(
        borderColor = if (isModified) {
            LyricoColors.modifiedBorder
        } else {
            MiuixTheme.colorScheme.primary
        }
    )

    if (isMultiline) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))

                AnimatedVisibility(
                    visible = isModified,
                    enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(LyricoColors.modifiedBadgeBackground.copy(alpha = 0.8f))
                            .clickable { onRevert() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.action_undo_changes),
                            fontSize = 11.sp,
                            color = LyricoColors.modifiedText,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
                actionButtons()
            }

            TextField(
                state = state,
                textStyle = MiuixTheme.textStyles.body2,
                modifier = Modifier.fillMaxWidth(),
                label = fieldLabel,
                colors = colors,
                lineLimits = TextFieldLineLimits.MultiLine(
                    minHeightInLines = 10,
                    maxHeightInLines = if (limitMultilineLines) {
                        LIMITED_LYRICS_INPUT_MAX_LINES
                    } else {
                        Int.MAX_VALUE
                    }
                )
            )
        }
    } else {
        TextField(
            state = state,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            label = fieldLabel,
            colors = colors,
            trailingIcon = if (isModified) {
                {
                    IconButton(onClick = onRevert) {
                        Icon(
                            imageVector = MiuixIcons.Undo,
                            contentDescription = "Undo"
                        )
                    }
                }
            } else {
                null
            }
        )
    }
}

@Composable
private fun CustomMetadataFieldEditor(
    field: CustomTagField,
    isModified: Boolean,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
    onRevert: () -> Unit
) {
    val fieldState = rememberMetadataTextFieldState(
        value = field.value,
        onValueChange = onValueChange
    )

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isModified) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(LyricoColors.modifiedBadgeBackground.copy(alpha = 0.8f))
                        .clickable { onRevert() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.action_undo_changes),
                        fontSize = 11.sp,
                        color = LyricoColors.modifiedText,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    6.dp,
                    Alignment.End
                )
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable {
                            onRemove()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(Res.string.action_delete),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        MetadataInputField(
            label = field.key,
            state = fieldState,
            isModified = false,
            onRevert = onRevert
        )
    }
}

@Composable
private fun rememberMetadataTextFieldState(
    value: String,
    onValueChange: (String) -> Unit
): TextFieldState {
    val state = rememberTextFieldState(initialText = value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val textSyncState = remember(state) { MetadataTextSyncState(value) }

    LaunchedEffect(value, state) {
        if (value != textSyncState.lastTextSentToModel && state.text.toString() != value) {
            textSyncState.lastTextSentToModel = value
            state.edit {
                replace(0, length, value)
            }
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .distinctUntilChanged()
            .collectLatest { text ->
                if (text != textSyncState.lastTextSentToModel) {
                    textSyncState.lastTextSentToModel = text
                    latestOnValueChange(text)
                }
            }
    }
    return state
}

private class MetadataTextSyncState(
    var lastTextSentToModel: String
)

private data class SimpleTextField(
    val state: TextFieldState,
    val valueOf: (AudioTagData) -> String?,
    val write: AudioTagData.(String?) -> AudioTagData,
)

private fun editedValue(data: AudioTagData?, field: SimpleTextField): String =
    data?.let(field.valueOf).orEmpty()
