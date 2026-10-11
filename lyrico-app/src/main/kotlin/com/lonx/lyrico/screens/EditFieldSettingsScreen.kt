package com.lonx.lyrico.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.cancel
import com.lonx.lyrico.resources.cd_more_actions
import com.lonx.lyrico.resources.common_delete
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.custom_tag_add_key
import com.lonx.lyrico.resources.custom_tag_delete_message
import com.lonx.lyrico.resources.custom_tag_delete_title
import com.lonx.lyrico.resources.custom_tag_key
import com.lonx.lyrico.resources.custom_tag_key_hint
import com.lonx.lyrico.resources.custom_tag_key_invalid
import com.lonx.lyrico.resources.custom_tag_library_empty
import com.lonx.lyrico.resources.custom_tag_library_section
import com.lonx.lyrico.resources.duplicate_item
import com.lonx.lyrico.resources.edit_field_component_hint
import com.lonx.lyrico.resources.edit_field_component_short_tag
import com.lonx.lyrico.resources.edit_field_custom_short_tag
import com.lonx.lyrico.resources.edit_field_manage_members
import com.lonx.lyrico.resources.edit_field_settings_enabled_count
import com.lonx.lyrico.resources.edit_field_settings_hint
import com.lonx.lyrico.resources.edit_field_settings_reset_message
import com.lonx.lyrico.resources.edit_field_settings_title
import com.lonx.lyrico.resources.edit_field_songs_empty
import com.lonx.lyrico.resources.edit_field_songs_truncated
import com.lonx.lyrico.resources.edit_field_songs_with
import com.lonx.lyrico.resources.edit_field_songs_without
import com.lonx.lyrico.resources.edit_field_view_songs
import com.lonx.lyrico.resources.group_replay_gain
import com.lonx.lyrico.resources.invalid_empty_input
import com.lonx.lyrico.resources.load_failed
import com.lonx.lyrico.resources.reset_to_default
import com.lonx.lyrico.data.editfield.EditFieldBlock
import com.lonx.lyrico.data.editfield.EditFieldDefinition
import com.lonx.lyrico.data.editfield.EditFieldListItem
import com.lonx.lyrico.data.editfield.EditFieldRegistry
import com.lonx.lyrico.data.editfield.toEditFieldBlocks
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.ui.components.FieldOrderState
import com.lonx.lyrico.ui.components.ManagedChip
import com.lonx.lyrico.ui.components.base.PillButton
import com.lonx.lyrico.ui.components.base.PillButtonDefaults
import com.lonx.lyrico.ui.components.base.PillButtonSize
import com.lonx.lyrico.ui.components.base.YesNoDialog
import com.lonx.lyrico.ui.components.blur.BlurredTopBar
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.ui.components.library.LibraryEmptyState
import com.lonx.lyrico.ui.components.scaffoldContentPadding
import com.lonx.lyrico.ui.components.song.SongListItem
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.viewmodel.CustomTagKeyError
import com.lonx.lyrico.viewmodel.EditFieldSettingsViewModel
import com.lonx.lyrico.ui.navigation.EditMetadataDestination
import kotlinx.coroutines.launch
import com.lonx.lyrico.utils.formattedStringResource
import org.koin.compose.viewmodel.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup

/** 内置字段与自定义标签共用一份可排序列表。 */
@Composable
fun EditFieldSettingsScreen(
    navigator: Navigator,
) {
    val viewModel: EditFieldSettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteKey by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showSongsSheet by remember { mutableStateOf(false) }
    var pendingOpenSongUri by remember { mutableStateOf<String?>(null) }

    var componentKey by remember { mutableStateOf<String?>(null) }
    var showComponentSheet by remember { mutableStateOf(false) }
    var pendingFieldSongs by remember { mutableStateOf<EditFieldDefinition?>(null) }
    val blocks = remember(uiState.items) { uiState.items.map { it.field }.toEditFieldBlocks() }
    val selectedComponent = blocks.firstOrNull { it.key == componentKey }

    val scrollBehavior = MiuixScrollBehavior()
    val topBarBackdrop = rememberBarBlurBackdrop()
    val lazyListState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    val savedOrder = blocks.map { it.key }
    val orderState = remember { FieldOrderState(savedOrder) }
    val displayCodes = orderState.order
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (orderState.move(from.key, to.key)) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    LaunchedEffect(savedOrder) { orderState.updateSaved(savedOrder) }

    Scaffold(
        topBar = {
            BlurredTopBar(backdrop = topBarBackdrop) {
                SmallTopAppBar(
                    title = stringResource(Res.string.edit_field_settings_title),
                    color = Color.Transparent,
                    defaultWindowInsetsPadding = false,
                    navigationIcon = {
                        IconButton(onClick = { navigator.popBackStack() }) {
                            Icon(MiuixIcons.Back, stringResource(Res.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showResetDialog = true }) {
                            Icon(MiuixIcons.Reset, stringResource(Res.string.reset_to_default))
                        }
                        IconButton(onClick = {
                            viewModel.clearInputError()
                            showAddDialog = true
                        }) {
                            Icon(
                                MiuixIcons.Add,
                                contentDescription = stringResource(Res.string.custom_tag_add_key),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .blurSource(topBarBackdrop)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .fillMaxHeight(),
            contentPadding = scaffoldContentPadding(
                paddingValues = paddingValues,
                bottomExtra = 12.dp,
            ),
            overscrollEffect = null,
        ) {
            item(key = "hint") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = stringResource(Res.string.edit_field_settings_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = formattedStringResource(
                            Res.string.edit_field_settings_enabled_count,
                            uiState.enabledCount,
                            uiState.items.size,
                        ),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        maxLines = 1,
                    )
                }
            }

            items(
                items = displayCodes,
                key = { code -> code },
            ) { code ->
                val block = blocks.firstOrNull { it.key == code }
                val item = uiState.items.firstOrNull { it.field.code == block?.fields?.firstOrNull()?.code }

                ReorderableItem(state = reorderableState, key = code) {
                    if (item != null) {
                        EditFieldRow(
                            item = item,
                            component = block?.takeIf { it.isComposite },
                            checked = if (block?.isComposite == true) uiState.componentOverrides[block.key] ?: true else item.enabled,
                            onManageComponent = {
                                componentKey = block?.key
                                showComponentSheet = true
                            },
                            onCheckedChange = { checked ->
                                if (block?.isComposite == true) viewModel.setComponentEnabled(block.key, checked)
                                else viewModel.setEnabled(item.field.code, checked)
                            },
                            onDelete = {
                                EditFieldRegistry.customTagKeyOf(item.field.code)
                                    ?.let { pendingDeleteKey = it }
                            },
                            onOpenSongs = {
                                // 上一次关闭动画没走完就再打开时，丢弃待跳转的歌曲
                                pendingOpenSongUri = null
                                val composite = block?.isComposite == true
                                viewModel.showFieldSongs(
                                    if (composite) item.field.copy(titleRes = Res.string.group_replay_gain) else item.field,
                                    component = composite,
                                )
                                showSongsSheet = true
                            },
                            dragHandleModifier = Modifier.longPressDraggableHandle(
                                onDragStarted = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    viewModel.setBlockOrder(orderState.order)
                                },
                            ),
                        )
                    }
                }
            }


        }
    }

    AddCustomTagDialog(
        show = showAddDialog,
        availableKeys = uiState.availableKeys,
        error = uiState.inputError,
        onAddAvailable = viewModel::addAvailableKey,
        onDismiss = { showAddDialog = false },
        onDismissFinished = viewModel::clearInputError,
        onConfirm = { typed ->
            scope.launch {
                if (viewModel.addCustomTag(typed)) showAddDialog = false
            }
        },
    )

    YesNoDialog(
        show = pendingDeleteKey != null,
        title = stringResource(Res.string.custom_tag_delete_title),
        summary = formattedStringResource(Res.string.custom_tag_delete_message, pendingDeleteKey.orEmpty()),
        confirmText = stringResource(Res.string.common_delete),
        onDismissRequest = { pendingDeleteKey = null },
        onConfirm = {
            pendingDeleteKey?.let(viewModel::removeCustomTag)
            pendingDeleteKey = null
        },
    )

    YesNoDialog(
        show = showResetDialog,
        title = stringResource(Res.string.reset_to_default),
        summary = stringResource(Res.string.edit_field_settings_reset_message),
        confirmText = stringResource(Res.string.confirm),
        onDismissRequest = { showResetDialog = false },
        onConfirm = {
            viewModel.resetAll()
            orderState.resetPending()
            showResetDialog = false
        },
    )

    ComponentFieldsBottomSheet(
        show = showComponentSheet,
        component = selectedComponent,
        items = uiState.items,
        onEnabledChange = viewModel::setEnabled,
        onOrderChange = { codes -> componentKey?.let { viewModel.setComponentOrder(it, codes) } },
        onOpenSongs = { field ->
            pendingFieldSongs = field
            showComponentSheet = false
        },
        onDismissRequest = { showComponentSheet = false },
        onDismissFinished = {
            if (!showComponentSheet) {
                componentKey = null
                pendingFieldSongs?.let { field ->
                    pendingOpenSongUri = null
                    viewModel.showFieldSongs(field)
                    showSongsSheet = true
                }
                pendingFieldSongs = null
            }
        },
    )

    FieldSongsBottomSheet(
        show = showSongsSheet,
        sheetTitle = uiState.selectedField.songSheetTitle(),
        songsWithField = uiState.songsWithField,
        songsWithoutField = uiState.songsWithoutField,
        isLoading = uiState.isLoadingSongs,
        withTruncated = uiState.songsWithFieldTruncated,
        withoutTruncated = uiState.songsWithoutFieldTruncated,
        loadFailed = uiState.songsLoadFailed,
        onDismissRequest = { showSongsSheet = false },
        onDismissFinished = {
            // 关闭动画没走完又重新打开了列表时不清理、不跳转
            if (!showSongsSheet) {
                viewModel.clearFieldSongs()
                // 打开歌曲要等 sheet 收完，否则动画会被页面跳转打断
                pendingOpenSongUri?.let {
                    navigator.navigate(EditMetadataDestination(songFileUri = it))
                }
                pendingOpenSongUri = null
            }
        },
        onSongClick = { song ->
            pendingOpenSongUri = song.uri
            showSongsSheet = false
        },
    )
}

/** 自定义标签的键名；标准字段没有键名，返回空串。 */
private fun EditFieldDefinition?.customTagKey(): String =
    this?.let { EditFieldRegistry.customTagKeyOf(it.code) }.orEmpty()

/** 歌曲列表的标题：自定义标签用键名，标准字段用字段名。 */
@Composable
private fun EditFieldDefinition?.songSheetTitle(): String = when {
    this == null -> ""
    custom -> customTagKey()
    else -> stringResource(titleRes)
}

@Composable
private fun EditFieldRow(
    item: EditFieldListItem,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenSongs: () -> Unit,
    dragHandleModifier: Modifier,
    component: EditFieldBlock? = null,
    checked: Boolean = item.enabled,
    onManageComponent: () -> Unit = {},
    inSheet: Boolean = false,
) {
    var showMenu by remember { mutableStateOf(false) }
    val menuItems = buildList<Pair<String, () -> Unit>> {
        if (component != null) add(stringResource(Res.string.edit_field_manage_members) to onManageComponent)
        add(stringResource(Res.string.edit_field_view_songs) to onOpenSongs)
        if (item.field.custom) add(stringResource(Res.string.common_delete) to onDelete)
    }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().then(dragHandleModifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = component?.title() ?: item.field.songSheetTitle(),
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (component != null || item.field.custom) {
                    FieldTagChip(stringResource(
                        if (component != null) Res.string.edit_field_component_short_tag
                        else Res.string.edit_field_custom_short_tag
                    ))
                }
            }
            Box {
                PillButton(
                    text = stringResource(Res.string.cd_more_actions),
                    showText = false,
                    style = PillButtonDefaults.style(PillButtonSize.Small),
                    onClick = { showMenu = true },
                    leading = { Icon(MiuixIcons.More, stringResource(Res.string.cd_more_actions), Modifier.size(20.dp)) },
                )
                WindowListPopup(
                    show = showMenu,
                    popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                    onDismissRequest = { showMenu = false },
                    alignment = PopupPositionProvider.Align.End,
                ) {
                    ListPopupColumn {
                        menuItems.forEachIndexed { index, (label, action) ->
                            DropdownImpl(
                                text = label,
                                isSelected = false,
                                optionSize = menuItems.size,
                                index = index,
                                onSelectedIndexChange = {
                                    showMenu = false
                                    action()
                                },
                            )
                        }
                    }
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
    if (inSheet) content() else Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        insideMargin = PaddingValues(0.dp),
    ) { content() }
}

@Composable
private fun EditFieldBlock.title(): String = stringResource(
    if (isComposite) Res.string.group_replay_gain else fields.first().titleRes
)

@Composable
private fun ComponentFieldsBottomSheet(
    show: Boolean,
    component: EditFieldBlock?,
    items: List<EditFieldListItem>,
    onEnabledChange: (String, Boolean) -> Unit,
    onOrderChange: (List<String>) -> Unit,
    onOpenSongs: (EditFieldDefinition) -> Unit,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    WindowBottomSheet(
        show = show,
        title = component?.title().orEmpty(),
        enableNestedScroll = false,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        val savedCodes = component?.fields?.map { it.code }.orEmpty()
        val orderState = remember(component?.key) { FieldOrderState(savedCodes) }
        val displayCodes = orderState.order
        val latestOnOrderChange by rememberUpdatedState(onOrderChange)
        val listState = rememberLazyListState()
        val haptic = LocalHapticFeedback.current
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
            if (orderState.move(from.key, to.key)) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        LaunchedEffect(savedCodes) { orderState.updateSaved(savedCodes) }
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                stringResource(Res.string.edit_field_component_hint),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(0.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    overscrollEffect = null,
                ) {
                    items(displayCodes, key = { it }) { code ->
                        val item = items.firstOrNull { it.field.code == code }
                        ReorderableItem(reorderState, key = code) {
                            if (item != null) EditFieldRow(
                                item = item,
                                inSheet = true,
                                onCheckedChange = { onEnabledChange(code, it) },
                                onDelete = {},
                                onOpenSongs = { onOpenSongs(item.field) },
                                dragHandleModifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDragStopped = { latestOnOrderChange(orderState.order) },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 字段行上的小标记，用来把自定义标签和内置字段区分开。 */
@Composable
private fun FieldTagChip(text: String) {
    Text(
        text = text,
        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 两类歌曲共用固定高度的 Pager，切换空列表、加载状态时保持 sheet 高度。 */
@Composable
private fun FieldSongsBottomSheet(
    show: Boolean,
    sheetTitle: String,
    songsWithField: List<SongEntity>,
    songsWithoutField: List<SongEntity>,
    isLoading: Boolean,
    withTruncated: Boolean,
    withoutTruncated: Boolean,
    loadFailed: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    onSongClick: (SongEntity) -> Unit,
) {
    // 安卓按 `LocalConfiguration.screenHeightDp` 的 55% 算 sheet 里的列表高度（封顶 420dp）。
    // 桌面端没有那个配置对象，而且窗口是可缩放的：直接用一个固定上限，让 sheet 自己按内容排。
    // 这是换写法而不是做不到 —— sheet 在两种屏幕上都只是“别把窗口占满”。
    val pagerHeight = 420.dp
    WindowBottomSheet(
        show = show,
        title = sheetTitle,
        enableNestedScroll = false,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
    ) {
        val pagerState = rememberPagerState(pageCount = { 2 })
        val scope = rememberCoroutineScope()
        LaunchedEffect(show, sheetTitle) {
            if (show) pagerState.scrollToPage(0)
        }
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            TabRowWithContour(
                tabs = listOf(
                    stringResource(Res.string.edit_field_songs_with),
                    stringResource(Res.string.edit_field_songs_without),
                ),
                selectedTabIndex = pagerState.currentPage,
                onTabSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            )
            Spacer(Modifier.height(12.dp))
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(pagerHeight),
                beyondViewportPageCount = 1,
                key = { page -> "$sheetTitle:$page" },
                verticalAlignment = Alignment.Top,
            ) { page ->
                val songs = if (page == 0) songsWithField else songsWithoutField
                val truncated = if (page == 0) withTruncated else withoutTruncated
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        isLoading -> CircularProgressIndicator(size = 32.dp)
                        songs.isEmpty() -> LibraryEmptyState(
                            title = stringResource(if (loadFailed) Res.string.load_failed else Res.string.edit_field_songs_empty),
                        )
                        else -> Card(
                            modifier = Modifier.fillMaxSize(),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            LazyColumn(
                                state = rememberLazyListState(),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 16.dp),
                            ) {
                                if (loadFailed) {
                                    item { Text(stringResource(Res.string.load_failed), modifier = Modifier.padding(16.dp)) }
                                }
                                items(songs, key = { it.uri }) { song ->
                                    SongListItem(song = song, onClick = { onSongClick(song) })
                                }
                                if (truncated) {
                                    item {
                                        Text(
                                            text = stringResource(Res.string.edit_field_songs_truncated),
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                                            modifier = Modifier.padding(16.dp),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCustomTagDialog(
    show: Boolean,
    availableKeys: List<String>,
    error: CustomTagKeyError?,
    onAddAvailable: (String) -> Unit,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var typed by remember(show) { mutableStateOf("") }

    WindowDialog(
        show = show,
        title = stringResource(Res.string.custom_tag_add_key),
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    if (availableKeys.isEmpty()) {
                        Res.string.custom_tag_library_empty
                    } else {
                        Res.string.custom_tag_library_section
                    }
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            )

            if (availableKeys.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableKeys.forEach { key ->
                        ManagedChip(text = key, onClick = { onAddAvailable(key) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextField(
                value = typed,
                onValueChange = { typed = it },
                label = stringResource(Res.string.custom_tag_key),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (error) {
                    null -> stringResource(Res.string.custom_tag_key_hint)
                    CustomTagKeyError.EMPTY -> stringResource(Res.string.invalid_empty_input)
                    CustomTagKeyError.INVALID -> stringResource(Res.string.custom_tag_key_invalid)
                    CustomTagKeyError.DUPLICATE -> stringResource(Res.string.duplicate_item)
                },
                color = if (error == null) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.error
                },
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(Res.string.confirm),
                    onClick = { onConfirm(typed) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
