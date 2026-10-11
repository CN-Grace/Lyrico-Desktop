package com.lonx.lyrico.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_abort
import com.lonx.lyrico.resources.action_add_custom_tag
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.action_undo_changes
import com.lonx.lyrico.resources.batch_edit_cover_change
import com.lonx.lyrico.resources.batch_edit_cover_hint
import com.lonx.lyrico.resources.batch_edit_cover_remove
import com.lonx.lyrico.resources.batch_edit_cover_revert
import com.lonx.lyrico.resources.batch_edit_info_content
import com.lonx.lyrico.resources.batch_edit_info_summary
import com.lonx.lyrico.resources.batch_edit_loading_selected_values
import com.lonx.lyrico.resources.batch_edit_no_selected_covers
import com.lonx.lyrico.resources.batch_edit_no_selected_values
import com.lonx.lyrico.resources.batch_edit_preview_change_count
import com.lonx.lyrico.resources.batch_edit_preview_current_cover
import com.lonx.lyrico.resources.batch_edit_preview_empty
import com.lonx.lyrico.resources.batch_edit_preview_empty_value
import com.lonx.lyrico.resources.batch_edit_preview_new_value
import com.lonx.lyrico.resources.batch_edit_preview_old_value
import com.lonx.lyrico.resources.batch_edit_preview_remove_cover
import com.lonx.lyrico.resources.batch_edit_processing
import com.lonx.lyrico.resources.batch_edit_select_cover_from_selected_songs
import com.lonx.lyrico.resources.batch_edit_select_from_selected_songs
import com.lonx.lyrico.resources.batch_edit_song_count
import com.lonx.lyrico.resources.batch_edit_tab_config
import com.lonx.lyrico.resources.batch_edit_tab_preview
import com.lonx.lyrico.resources.batch_edit_title
import com.lonx.lyrico.resources.batch_matching_failure
import com.lonx.lyrico.resources.batch_matching_success
import com.lonx.lyrico.resources.batch_matching_total_time
import com.lonx.lyrico.resources.batch_replay_gain_skipped
import com.lonx.lyrico.resources.cancel
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.edit_field_settings_title
import com.lonx.lyrico.resources.ic_album_24dp
import com.lonx.lyrico.resources.ic_filled_star_24dp
import com.lonx.lyrico.resources.ic_outline_star_24dp
import com.lonx.lyrico.resources.label_custom_tag_name
import com.lonx.lyrico.resources.label_custom_tag_value
import com.lonx.lyrico.resources.label_select_same_album_cover
import com.lonx.lyrico.data.editfield.toEditFieldBlocks
import com.lonx.lyrico.data.editfield.EditFieldRegistry
import com.lonx.lyrico.ui.components.rememberTintedPainter
import com.lonx.lyrico.ui.theme.LyricoColors
import com.lonx.lyrico.ui.components.CoverRequest
import com.lonx.lyrico.ui.components.base.LyricsOffsetField
import com.lonx.lyrico.ui.components.fab.ExpandableFabMenu
import com.lonx.lyrico.ui.components.fab.ExpandableFabMenuStyle
import com.lonx.lyrico.ui.components.fab.FabMenuItem
import com.lonx.lyrico.ui.components.scaffoldBottomPadding
import com.lonx.lyrico.ui.components.scaffoldTopHorizontalPadding
import com.lonx.lyrico.viewmodel.BatchEditField
import com.lonx.lyrico.viewmodel.BatchEditPreview
import com.lonx.lyrico.viewmodel.BatchEditSelectableCover
import com.lonx.lyrico.viewmodel.BatchEditSelectableValue
import com.lonx.lyrico.viewmodel.BatchEditUiState
import com.lonx.lyrico.viewmodel.BatchEditViewModel
import com.lonx.lyrico.ui.navigation.EditFieldSettingsDestination
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.platform.FileOpenPicker
import com.lonx.lyrico.platform.rememberFileOpenPicker
import com.lonx.lyrico.utils.formattedStringResource
import java.io.File
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Undo
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.foundation.lazy.grid.items as gridItems

private val LocalImageExtensions = listOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

private enum class BatchEditTab(val labelRes: StringResource) {
    Config(Res.string.batch_edit_tab_config),
    Preview(Res.string.batch_edit_tab_preview)
}

@Composable
fun BatchEditScreen(
    navigator: Navigator,
    // Nullable so a test can inject a fake (see `SongsPage` for why it is not a default argument).
    fileOpenPicker: FileOpenPicker? = null,
) {
    val viewModel: BatchEditViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 字段顺序与显隐来自「编辑字段」配置，页面按这份列表遍历渲染。
    val visibleFields by viewModel.visibleFields.collectAsStateWithLifecycle()
    val visibleFieldCodes = visibleFields.map { it.code }.toSet()
    val fieldBlocks = remember(visibleFields) { visibleFields.toEditFieldBlocks() }

    var showCoverOptionsSheet by remember { mutableStateOf(false) }
    var showSelectedCoverSheet by remember { mutableStateOf(false) }
    var selectedCoverOptions by remember { mutableStateOf<List<BatchEditSelectableCover>>(emptyList()) }
    var selectedCoverOptionsLoading by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showAddCustomTagDialog by remember { mutableStateOf(false) }
    var showSelectedValueSheet by remember { mutableStateOf(false) }
    var selectedValueField by remember { mutableStateOf<BatchEditField?>(null) }
    var selectedCustomValueKey by remember { mutableStateOf<String?>(null) }
    var selectedValueOptions by remember { mutableStateOf<List<BatchEditSelectableValue>>(emptyList()) }
    var selectedValueOptionsLoading by remember { mutableStateOf(false) }
    var expandedFabMenu by remember { mutableStateOf(false) }

    val tabs = remember { BatchEditTab.entries }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val currentTab = tabs[pagerState.currentPage]
    val editPreviews = remember(uiState, visibleFieldCodes) {
        viewModel.buildEditPreviews(visibleFieldCodes)
    }

    // 自定义标签的预览值只需要可见的自定义标签，从同一份字段列表里取。
    val visibleCustomKeys = remember(visibleFields) {
        visibleFields.filter { it.custom }
            .mapNotNull { EditFieldRegistry.customTagKeyOf(it.code) }
    }

    LaunchedEffect(visibleCustomKeys, uiState.selectedSongsVersion) {
        viewModel.loadCustomTagPreviewValues(visibleCustomKeys)
    }

    // 桌面端没有 `PickVisualMedia`：选封面是原生文件对话框（挂起），拿到的就是 `File`。
    val coverPicker = fileOpenPicker ?: rememberFileOpenPicker(
        title = stringResource(Res.string.batch_edit_cover_change),
        extensions = LocalImageExtensions
    )

    val scope = rememberCoroutineScope()

    fun applySelectedValue(field: BatchEditField, value: String) {
        when (field) {
            BatchEditField.TITLE -> viewModel.updateTitle(value)
            BatchEditField.ARTIST -> viewModel.updateArtist(value)
            BatchEditField.ALBUM_ARTIST -> viewModel.updateAlbumArtist(value)
            BatchEditField.ALBUM -> viewModel.updateAlbum(value)
            BatchEditField.DATE -> viewModel.updateDate(value)
            BatchEditField.LANGUAGE -> viewModel.updateLanguage(value)
            BatchEditField.GENRE -> viewModel.updateGenre(value)
            BatchEditField.TRACK_NUMBER -> viewModel.updateTrackNumber(value)
            BatchEditField.DISC_NUMBER -> viewModel.updateDiscNumber(value)
            BatchEditField.COMPOSER -> viewModel.updateComposer(value)
            BatchEditField.LYRICIST -> viewModel.updateLyricist(value)
            BatchEditField.COPYRIGHT -> viewModel.updateCopyright(value)
            BatchEditField.COMMENT -> viewModel.updateComment(value)
            BatchEditField.LYRICS -> viewModel.updateLyrics(value)
            BatchEditField.REPLAY_GAIN_TRACK_GAIN -> viewModel.updateReplayGainTrackGain(value)
            BatchEditField.REPLAY_GAIN_TRACK_PEAK -> viewModel.updateReplayGainTrackPeak(value)
            BatchEditField.REPLAY_GAIN_ALBUM_GAIN -> viewModel.updateReplayGainAlbumGain(value)
            BatchEditField.REPLAY_GAIN_ALBUM_PEAK -> viewModel.updateReplayGainAlbumPeak(value)
            BatchEditField.REPLAY_GAIN_REFERENCE_LOUDNESS -> viewModel.updateReplayGainReferenceLoudness(value)
            BatchEditField.RATING -> Unit
            BatchEditField.COVER -> Unit
        }
    }

    fun openSelectedValueSheet(field: BatchEditField) {
        selectedValueField = field
        selectedCustomValueKey = null
        selectedValueOptions = emptyList()
        selectedValueOptionsLoading = true
        showSelectedValueSheet = true
        scope.launch {
            selectedValueOptions = viewModel.getSelectedSongFieldValues(field)
            selectedValueOptionsLoading = false
        }
    }

    fun openSelectedCustomValueSheet(key: String) {
        selectedValueField = null
        selectedCustomValueKey = key
        selectedValueOptions = emptyList()
        selectedValueOptionsLoading = true
        showSelectedValueSheet = true
        scope.launch {
            selectedValueOptions = viewModel.getSelectedSongCustomTagValues(key)
            selectedValueOptionsLoading = false
        }
    }

    fun applyCoverSource(cover: Any) {
        when (cover) {
            // A path from the album's own cover: on desktop that *is* the file.
            is String -> viewModel.updateCover(File(cover))
            is ByteArray -> {
                val tempFile = File.createTempFile("cover", ".jpg")
                tempFile.writeBytes(cover)
                viewModel.updateCover(tempFile)
            }
        }
    }

    fun pickCover() {
        scope.launch {
            val file = coverPicker.pick() ?: return@launch
            viewModel.updateCover(file)
        }
    }

    fun openSelectedCoverSheet() {
        selectedCoverOptions = emptyList()
        selectedCoverOptionsLoading = true
        showSelectedCoverSheet = true
        scope.launch {
            selectedCoverOptions = viewModel.getSelectedSongCovers()
            selectedCoverOptionsLoading = false
        }
    }

    // 加载同专辑封面（直接使用第一张）
    fun loadSameAlbumCovers() {
        scope.launch {
            try {
                val covers = viewModel.getSameAlbumCovers()
                if (covers.isNotEmpty()) {
                    val (_, cover) = covers.first()
                    cover?.let { applyCoverSource(it) }
                }
            } catch (e: Exception) {
                // 错误处理
            }
        }
    }

    val topAppBarScrollBehavior = MiuixScrollBehavior()

    // 桌面端没有系统返回手势（见 `FolderManagerScreen` / `AlbumDetailScreen`）：菜单靠遮罩关、选择模式
    // 靠顶栏关闭按钮退。
    Box(
        modifier = Modifier.fillMaxSize()
    ){
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                SmallTopAppBar(
                    title = stringResource(Res.string.batch_edit_title),
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (!uiState.isSaving) navigator.popBackStack()
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(Res.string.action_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.saveBatchEdit() },
                            enabled = !uiState.isSaving
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Ok,
                                contentDescription = null
                            )
                        }
                    },
                    scrollBehavior = topAppBarScrollBehavior
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scaffoldTopHorizontalPadding(paddingValues))
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp)
                    ) {
                        val tabLabels = tabs.map { tab ->
                            when (tab) {
                                BatchEditTab.Config -> stringResource(tab.labelRes)
                                BatchEditTab.Preview -> formattedStringResource(
                                    tab.labelRes,
                                    editPreviews.size
                                )
                            }
                        }
                        TabRowWithContour(
                            tabs = tabLabels,
                            selectedTabIndex = pagerState.currentPage,
                            onTabSelected = { index ->
                                scope.launch {
                                    expandedFabMenu = false
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        key = { index -> tabs[index].name }
                    ) { page ->
                        when (tabs[page]) {
                            BatchEditTab.Config ->
                                LazyColumn(
                                    modifier = Modifier
                                        .scrollEndHaptic()
                                        .overScrollVertical()
                                        .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                                        .fillMaxHeight()
                                        .imePadding(),
                                    contentPadding = PaddingValues(
                                        bottom = scaffoldBottomPadding(paddingValues, bottomExtra = 80.dp),
                                    ),
                                    overscrollEffect = null,
                                ) {
                                    // 歌曲数量信息
                                    item(key = "song_count") {
                                        SmallTitle(
                                            text = formattedStringResource(
                                                Res.string.batch_edit_song_count,
                                                uiState.songCount
                                            )
                                        )
                                    }

                                    fieldBlocks.forEach { block ->
                                        val field = block.fields.first()
                                        val fieldCode = field.code

                                        // 自定义标签没有内置字段枚举，单独渲染。
                                        if (field.custom) {
                                            val key = EditFieldRegistry.customTagKeyOf(fieldCode)
                                            if (key != null) {
                                                item(key = fieldCode) {
                                                    Card(
                                                        modifier = Modifier.padding(
                                                            horizontal = 12.dp,
                                                            vertical = 6.dp
                                                        )
                                                    ) {
                                                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                                            BatchEditCustomFieldItem(
                                                                keyName = key,
                                                                value = uiState.customFields
                                                                    .firstOrNull { it.key == key }
                                                                    ?.value
                                                                    ?: "<keep>",
                                                                onValueChange = { value ->
                                                                    viewModel.setCustomFieldValue(
                                                                        key = key,
                                                                        value = value
                                                                    )
                                                                },
                                                                onKeep = {
                                                                    viewModel.keepCustomField(key)
                                                                },
                                                                onSelectFromSongs = {
                                                                    openSelectedCustomValueSheet(key)
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            return@forEach
                                        }

                                        item(key = block.key) {
                                            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                                    block.fields.forEach { member ->
                                                        val editField = BatchEditField.entries.firstOrNull { it.target == member.target }
                                                        when (member.code) {
                                                            // 封面和评级共用一张卡片，分别保留显隐开关。
                                                            "picture" -> BatchEditCoverSection(
                                                                coverUri = uiState.coverUri,
                                                                isRemoved = uiState.removeCover,
                                                                onCoverClick = {
                                                                    showCoverOptionsSheet = true
                                                                }
                                                            )

                                                            "rating" -> BatchEditRatingItem(
                                                                rating = uiState.rating,
                                                                isModified = uiState.ratingModified,
                                                                onRatingChange = {
                                                                    viewModel.updateRating(it)
                                                                },
                                                                onRevert = { viewModel.resetRating() }
                                                            )

                                                            // 歌词偏移是数值调整控件，不是标签字段；正负号由控件本身表达，无需额外说明。
                                                            "lyrics_offset" -> {
                                                                LyricsOffsetField(
                                                                    offset = uiState.lyricsOffset,
                                                                    onOffsetChange = {
                                                                        viewModel.updateLyricsOffset(it)
                                                                    },
                                                                    enabled = !uiState.isSaving,
                                                                    modifier = Modifier.padding(
                                                                        horizontal = 12.dp,
                                                                        vertical = 6.dp
                                                                    )
                                                                )
                                                            }

                                                            else -> BatchEditFieldItem(
                                                                field = requireNotNull(editField),
                                                                value = editField.currentValueIn(uiState),
                                                                onValueChange = { newValue ->
                                                                    editField.applyTo(viewModel, newValue)
                                                                },
                                                                onSelectFromSongs = {
                                                                    openSelectedValueSheet(editField)
                                                                },
                                                                isMultiline = editField == BatchEditField.LYRICS
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                }

                            BatchEditTab.Preview -> BatchEditPreviewTab(
                                previews = editPreviews,
                                bottomPadding = scaffoldBottomPadding(paddingValues),
                                topAppBarNestedScrollConnection = topAppBarScrollBehavior.nestedScrollConnection
                            )
                        }
                    }
                }
            }
        }
        ExpandableFabMenu(
            visible = currentTab == BatchEditTab.Config,
            expanded = expandedFabMenu,
            enabled = !uiState.isSaving,
            style = ExpandableFabMenuStyle.default().copy(
                mainIcon = MiuixIcons.Add
            ),
            itemCount = 3,
            onExpandedChange = { expandedFabMenu = it }
        ) {
            FabMenuItem(
                label = stringResource(Res.string.action_add_custom_tag),
                icon = MiuixIcons.Add,
                enabled = !uiState.isSaving,
                onClick = {
                    expandedFabMenu = false
                    showAddCustomTagDialog = true
                }
            )
            FabMenuItem(
                label = stringResource(Res.string.edit_field_settings_title),
                icon = MiuixIcons.Settings,
                enabled = !uiState.isSaving,
                onClick = {
                    expandedFabMenu = false
                    navigator.navigate(EditFieldSettingsDestination)
                }
            )
            FabMenuItem(
                label = stringResource(Res.string.batch_edit_info_summary),
                icon = MiuixIcons.Info,
                enabled = !uiState.isSaving,
                onClick = {
                    expandedFabMenu = false
                    showInfoDialog = true
                }
            )
        }
    }

    // 封面操作 BottomSheet
    WindowBottomSheet(
        show = showCoverOptionsSheet,
        onDismissRequest = { showCoverOptionsSheet = false }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .scrollEndHaptic()
                .overScrollVertical(),
            overscrollEffect = null
        ) {
            item {
                Card(
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
                ) {
                    ArrowPreference(
                        title = stringResource(Res.string.batch_edit_cover_change),
                        onClick = {
                            showCoverOptionsSheet = false
                            pickCover()
                        }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.batch_edit_select_cover_from_selected_songs),
                        onClick = {
                            showCoverOptionsSheet = false
                            openSelectedCoverSheet()
                        }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.label_select_same_album_cover),
                        onClick = {
                            showCoverOptionsSheet = false
                            loadSameAlbumCovers()
                        }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.batch_edit_cover_remove),
                        onClick = {
                            showCoverOptionsSheet = false
                            viewModel.removeCover()
                        }
                    )
                    if (uiState.coverUri != null || uiState.removeCover) {
                        ArrowPreference(
                            title = stringResource(Res.string.batch_edit_cover_revert),
                            onClick = {
                                showCoverOptionsSheet = false
                                viewModel.revertCover()
                            }
                        )
                    }
                }
            }
        }
    }

    WindowBottomSheet(
        show = showSelectedValueSheet,
        enableNestedScroll = false,
        title = selectedValueField?.let { stringResource(it.labelResId) }
            ?: selectedCustomValueKey.orEmpty(),
        onDismissRequest = { showSelectedValueSheet = false }
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 32.dp)
                .fillMaxWidth()
        ) {

                when {
                    selectedValueOptionsLoading || selectedValueOptions.isEmpty() -> {
                        Card(
                            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
                        ) {
                            BasicComponent(
                                title = stringResource(
                                    if (selectedValueOptionsLoading)
                                        Res.string.batch_edit_loading_selected_values
                                    else
                                        Res.string.batch_edit_no_selected_values
                                )
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .scrollEndHaptic()
                                .overScrollVertical(),
                            overscrollEffect = null
                        ) {
                            items(
                                items = selectedValueOptions,
                                key = { "${it.sourceUri}\n${it.value}" }
                            ) { option ->
                                BatchEditValueOptionItem(
                                    option = option,
                                    onClick = {
                                        selectedValueField?.let { field ->
                                            applySelectedValue(field, option.value)
                                        }
                                        selectedCustomValueKey?.let { key ->
                                            viewModel.setCustomFieldValue(key, option.value)
                                        }
                                        showSelectedValueSheet = false
                                    }
                                )
                            }
                        }
                    }
                }

        }
    }

    WindowBottomSheet(
        show = showSelectedCoverSheet,
        enableNestedScroll = false,
        title = stringResource(Res.string.batch_edit_select_cover_from_selected_songs),
        onDismissRequest = { showSelectedCoverSheet = false }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = PaddingValues(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            overscrollEffect = null
        ) {
            if (selectedCoverOptionsLoading || selectedCoverOptions.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
                    ) {
                        BasicComponent(
                            title = stringResource(
                                if (selectedCoverOptionsLoading)
                                    Res.string.batch_edit_loading_selected_values
                                else
                                    Res.string.batch_edit_no_selected_covers
                            )
                        )
                    }
                }
            }

            if (!selectedCoverOptionsLoading && selectedCoverOptions.isNotEmpty()) {
                gridItems(
                    items = selectedCoverOptions,
                    key = { "${it.sourceUri}\n${it.fileLastModified}" }
                ) { option ->
                    BatchEditCoverOptionItem(
                        option = option,
                        onClick = {
                            scope.launch {
                                viewModel.getSelectedSongCover(option.sourceUri)?.let { cover ->
                                    applyCoverSource(cover)
                                    showSelectedCoverSheet = false
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // 批量编辑说明对话框
    WindowDialog(
        show = showInfoDialog,
        title = stringResource(Res.string.batch_edit_info_summary),
        onDismissRequest = { showInfoDialog = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = stringResource(Res.string.batch_edit_info_content),
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(Res.string.confirm),
                onClick = { showInfoDialog = false },
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }

    // 保存进度对话框
    WindowBottomSheet(
        show = uiState.saveProgressBottomSheet,
        onDismissRequest = {
            if (!uiState.isSaving) viewModel.closeSaveBottomSheet()
        },
        onDismissFinished = {
            if (uiState.saveSuccess == true) {
                navigator.popBackStack()
            }
        },
        allowDismiss = !uiState.isSaving,
        title = stringResource(Res.string.batch_edit_title),
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier.padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 第一行：显示 标题/文件名 或 总用时
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.isSaving) {
                            uiState.currentFile.ifEmpty { stringResource(Res.string.batch_edit_processing) }
                        } else {
                            // 保存完成后显示总用时
                            formattedStringResource(Res.string.batch_matching_total_time, uiState.saveTimeMillis / 1000.0)
                        },
                        style = MiuixTheme.textStyles.subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "${uiState.saveProgress} / ${uiState.saveTotal}",
                        style = MiuixTheme.textStyles.main,
                        textAlign = TextAlign.End
                    )
                }

                LinearProgressIndicator(
                    progress = if (uiState.saveTotal > 0)
                        uiState.saveProgress.toFloat() / uiState.saveTotal
                    else 0f
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formattedStringResource(
                        Res.string.batch_matching_success,
                        uiState.successCount
                    ),
                    style = MiuixTheme.textStyles.main
                )
                Text(
                    text = formattedStringResource(
                        Res.string.batch_replay_gain_skipped,
                        uiState.skippedCount
                    ),
                    style = MiuixTheme.textStyles.main
                )
                Text(
                    text = formattedStringResource(
                        Res.string.batch_matching_failure,
                        uiState.failureCount
                    ),
                    style = MiuixTheme.textStyles.main
                )
            }

            TextButton(
                text = if (uiState.isSaving) stringResource(Res.string.action_abort) else stringResource(
                    Res.string.confirm
                ),
                onClick = {
                    if (uiState.isSaving) {
                        viewModel.abortSave()
                    } else {
                        viewModel.closeSaveBottomSheet()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )

        }
    }

    WindowDialog(
        show = showAddCustomTagDialog,
        title = stringResource(Res.string.action_add_custom_tag),
        onDismissRequest = { showAddCustomTagDialog = false }
    ) {
        var newCustomTagKey by remember { mutableStateOf("") }
        var newCustomTagValue by remember { mutableStateOf("") }
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = newCustomTagKey,
                onValueChange = { newCustomTagKey = it },
                label = stringResource(Res.string.label_custom_tag_name),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = newCustomTagValue,
                onValueChange = { newCustomTagValue = it },
                label = stringResource(Res.string.label_custom_tag_value),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = { showAddCustomTagDialog = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(Res.string.confirm),
                    onClick = {
                        if (newCustomTagKey.isNotBlank()) {
                            viewModel.addCustomFieldAndShow(
                                key = newCustomTagKey,
                                value = newCustomTagValue,
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

}

@Composable
private fun BatchEditCoverOptionItem(
    option: BatchEditSelectableCover,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .clickable { onClick() }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = CoverRequest(option.previewUri, option.fileLastModified),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(LyricoColors.coverPlaceholder),
            placeholder = rememberTintedPainter(
                painter = painterResource(Res.drawable.ic_album_24dp),
                tint = LyricoColors.coverPlaceholderIcon
            ),
            error = rememberTintedPainter(
                painter = painterResource(Res.drawable.ic_album_24dp),
                tint = LyricoColors.coverPlaceholderIcon
            )
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = option.title,
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (option.summary.isNotBlank()) {
            Text(
                text = option.summary,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BatchEditValueOptionItem(
    option: BatchEditSelectableValue,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.padding(vertical = 6.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = option.value,
                style = MiuixTheme.textStyles.main,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BatchEditRatingItem(
    rating: Int,
    isModified: Boolean,
    onRatingChange: (Int) -> Unit,
    onRevert: () -> Unit
) {
    val onSurfaceDim = MiuixTheme.colorScheme.onSurfaceVariantActions

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        BasicComponent(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp),
            endActions = {
                if (isModified) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.error.copy(alpha = 0.1f))
                            .clickable { onRevert() }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.action_undo_changes),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "<keep>",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            content = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 1..5) {
                        val isFilled = i <= rating

                        Icon(
                            painter = painterResource(
                                if (isFilled)
                                    Res.drawable.ic_filled_star_24dp
                                else
                                    Res.drawable.ic_outline_star_24dp
                            ),
                            contentDescription = null,
                            tint = if (isFilled)
                                MiuixTheme.colorScheme.primary
                            else
                                onSurfaceDim.copy(alpha = 0.4f),
                            modifier = Modifier
                                .size(28.dp)
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
        )
    }
}

/**
 * 封面编辑区
 */
@Composable
private fun BatchEditCoverSection(
    coverUri: Any?,
    isRemoved: Boolean,
    onCoverClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clickable { onCoverClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isRemoved) {
            Text(
                text = stringResource(Res.string.batch_edit_cover_remove),
                color = MiuixTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
        } else if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                placeholder = rememberTintedPainter(
                    painter = painterResource(Res.drawable.ic_album_24dp),
                    tint = LyricoColors.coverPlaceholderIcon
                ),
                error = rememberTintedPainter(
                    painter = painterResource(Res.drawable.ic_album_24dp),
                    tint = LyricoColors.coverPlaceholderIcon
                )
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_album_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.batch_edit_cover_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    fontSize = 13.sp
                )
            }
        }
    }
}


@Composable
private fun BatchEditPreviewTab(
    previews: List<BatchEditPreview>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    topAppBarNestedScrollConnection: NestedScrollConnection
) {
    var expandedPreviewKey by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(topAppBarNestedScrollConnection)
            .fillMaxHeight(),
        contentPadding = PaddingValues(bottom = bottomPadding + 12.dp),
        overscrollEffect = null,
    ) {
        if (previews.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.batch_edit_preview_empty),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        } else {
            items(
                items = previews,
                key = { it.songUri }
            ) { preview ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .animateContentSize()
                ) {
                    BasicComponent(
                        onClick = {
                            expandedPreviewKey =
                                if (expandedPreviewKey == preview.songUri) null else preview.songUri
                        }
                    ) {
                        Text(
                            text = preview.fileName,
                            style = MiuixTheme.textStyles.body1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formattedStringResource(Res.string.batch_edit_preview_change_count, preview.changes.size),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )

                        AnimatedVisibility(visible = expandedPreviewKey == preview.songUri) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                preview.changes.forEach { change ->
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = change.labelResId?.let { stringResource(it) } ?: change.customLabel.orEmpty(),
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = formattedStringResource(
                                                Res.string.batch_edit_preview_old_value,
                                                displayBatchEditPreviewValue(change.oldValue)
                                            ),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                                        )
                                        Text(
                                            text = formattedStringResource(
                                                Res.string.batch_edit_preview_new_value,
                                                displayBatchEditPreviewValue(change.newValue)
                                            ),
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }
    }
}



@Composable
private fun displayBatchEditPreviewValue(value: String): String {
    return when (value) {
        "<current_cover>" -> stringResource(Res.string.batch_edit_preview_current_cover)
        "<remove_cover>" -> stringResource(Res.string.batch_edit_preview_remove_cover)
        "" -> stringResource(Res.string.batch_edit_preview_empty_value)
        else -> value
    }
}


/**
 * 单个标签编辑字段
 * value 为 "<keep>" 时表示不修改该字段
 */
@Composable
private fun BatchEditFieldItem(
    field: BatchEditField,
    value: String,
    onValueChange: (String) -> Unit,
    onSelectFromSongs: () -> Unit,
    isMultiline: Boolean = false
) {
    val isKeep = value == "<keep>"

    if (isMultiline) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (isKeep) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .clickable { /* 无法恢复，已经是 <keep> */ }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "<keep>",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.error.copy(alpha = 0.1f))
                            .clickable { onValueChange("<keep>") }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.action_undo_changes),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                IconButton(onClick = onSelectFromSongs) {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowUpDown,
                        contentDescription = stringResource(Res.string.batch_edit_select_from_selected_songs)
                    )
                }
            }
            TextField(
                textStyle = MiuixTheme.textStyles.body2,
                modifier = Modifier.fillMaxWidth(),
                value = value,
                label = stringResource(field.labelResId) + if (isKeep) " (无修改)" else "",
                onValueChange = onValueChange,
                singleLine = false,
                minLines = 6
            )
        }
    } else {
        TextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            value = value,
            onValueChange = onValueChange,
            label = stringResource(field.labelResId) + if (isKeep) " (无修改)" else "",
            trailingIcon = {
                Row {
                    IconButton(onClick = {
                        onValueChange(if (isKeep) "" else "<keep>")
                    }) {
                        Icon(
                            imageVector = if (isKeep) MiuixIcons.Close else MiuixIcons.Undo,
                            contentDescription = null,
                            tint = if (isKeep)
                                MiuixTheme.colorScheme.primary
                            else
                                MiuixTheme.colorScheme.error
                        )
                    }
                    IconButton(onClick = onSelectFromSongs) {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowUpDown,
                            contentDescription = stringResource(Res.string.batch_edit_select_from_selected_songs)
                        )
                    }
                }
            }
        )
    }
}

/**
 * 自定义标签编辑字段
 */
@Composable
private fun BatchEditCustomFieldItem(
    keyName: String,
    value: String,
    onValueChange: (String) -> Unit,
    onKeep: () -> Unit,
    onSelectFromSongs: () -> Unit,
) {
    val isKeep = value == "<keep>"

    TextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        value = value,
        onValueChange = onValueChange,
        label = keyName + if (isKeep) " (无修改)" else "",
        trailingIcon = {
            Row {
                IconButton(onClick = {
                    if (isKeep) {
                        onValueChange("")
                    } else {
                        onKeep()
                    }
                }) {
                    Icon(
                        imageVector = if (isKeep) MiuixIcons.Close else MiuixIcons.Undo,
                        contentDescription = null,
                        tint = if (isKeep)
                            MiuixTheme.colorScheme.primary
                        else
                            MiuixTheme.colorScheme.error
                    )
                }
                IconButton(onClick = onSelectFromSongs) {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowUpDown,
                        contentDescription = stringResource(Res.string.batch_edit_select_from_selected_songs)
                    )
                }
            }
        }
    )
}

/** 批量编辑页当前值；未修改的字段是 `<keep>`。 */
private fun BatchEditField.currentValueIn(state: BatchEditUiState): String = when (this) {
    BatchEditField.TITLE -> state.title
    BatchEditField.ARTIST -> state.artist
    BatchEditField.ALBUM_ARTIST -> state.albumArtist
    BatchEditField.ALBUM -> state.album
    BatchEditField.DATE -> state.date
    BatchEditField.LANGUAGE -> state.language
    BatchEditField.GENRE -> state.genre
    BatchEditField.TRACK_NUMBER -> state.trackNumber
    BatchEditField.DISC_NUMBER -> state.discNumber
    BatchEditField.COMPOSER -> state.composer
    BatchEditField.LYRICIST -> state.lyricist
    BatchEditField.COPYRIGHT -> state.copyright
    BatchEditField.COMMENT -> state.comment
    BatchEditField.LYRICS -> state.lyrics
    BatchEditField.REPLAY_GAIN_TRACK_GAIN -> state.replayGainTrackGain
    BatchEditField.REPLAY_GAIN_TRACK_PEAK -> state.replayGainTrackPeak
    BatchEditField.REPLAY_GAIN_ALBUM_GAIN -> state.replayGainAlbumGain
    BatchEditField.REPLAY_GAIN_ALBUM_PEAK -> state.replayGainAlbumPeak
    BatchEditField.REPLAY_GAIN_REFERENCE_LOUDNESS -> state.replayGainReferenceLoudness
    // 封面与评级走各自的组合件渲染，不会走到这里。
    BatchEditField.COVER, BatchEditField.RATING -> "<keep>"
}

private fun BatchEditField.applyTo(viewModel: BatchEditViewModel, value: String) {
    when (this) {
        BatchEditField.TITLE -> viewModel.updateTitle(value)
        BatchEditField.ARTIST -> viewModel.updateArtist(value)
        BatchEditField.ALBUM_ARTIST -> viewModel.updateAlbumArtist(value)
        BatchEditField.ALBUM -> viewModel.updateAlbum(value)
        BatchEditField.DATE -> viewModel.updateDate(value)
        BatchEditField.LANGUAGE -> viewModel.updateLanguage(value)
        BatchEditField.GENRE -> viewModel.updateGenre(value)
        BatchEditField.TRACK_NUMBER -> viewModel.updateTrackNumber(value)
        BatchEditField.DISC_NUMBER -> viewModel.updateDiscNumber(value)
        BatchEditField.COMPOSER -> viewModel.updateComposer(value)
        BatchEditField.LYRICIST -> viewModel.updateLyricist(value)
        BatchEditField.COPYRIGHT -> viewModel.updateCopyright(value)
        BatchEditField.COMMENT -> viewModel.updateComment(value)
        BatchEditField.LYRICS -> viewModel.updateLyrics(value)
        BatchEditField.REPLAY_GAIN_TRACK_GAIN -> viewModel.updateReplayGainTrackGain(value)
        BatchEditField.REPLAY_GAIN_TRACK_PEAK -> viewModel.updateReplayGainTrackPeak(value)
        BatchEditField.REPLAY_GAIN_ALBUM_GAIN -> viewModel.updateReplayGainAlbumGain(value)
        BatchEditField.REPLAY_GAIN_ALBUM_PEAK -> viewModel.updateReplayGainAlbumPeak(value)
        BatchEditField.REPLAY_GAIN_REFERENCE_LOUDNESS ->
            viewModel.updateReplayGainReferenceLoudness(value)
        // 封面与评级走各自的组合件回调。
        BatchEditField.COVER, BatchEditField.RATING -> Unit
    }
}
