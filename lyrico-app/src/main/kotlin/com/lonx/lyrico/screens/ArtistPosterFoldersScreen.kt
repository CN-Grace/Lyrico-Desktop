package com.lonx.lyrico.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.platform.rememberDirectoryPicker
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_refresh_folder
import com.lonx.lyrico.resources.artist_poster_back
import com.lonx.lyrico.resources.artist_poster_folder_add
import com.lonx.lyrico.resources.artist_poster_folder_change
import com.lonx.lyrico.resources.artist_poster_folder_error
import com.lonx.lyrico.resources.artist_poster_folder_remove_tip
import com.lonx.lyrico.resources.artist_poster_folders
import com.lonx.lyrico.resources.artist_poster_folders_empty
import com.lonx.lyrico.resources.artist_poster_folders_help
import com.lonx.lyrico.resources.artist_poster_folders_summary
import com.lonx.lyrico.resources.artist_poster_naming
import com.lonx.lyrico.resources.cancel
import com.lonx.lyrico.resources.cd_more_actions
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.dialog_remove_folder_title
import com.lonx.lyrico.resources.folder_action_remove
import com.lonx.lyrico.ui.components.MiuixMarkdownText
import com.lonx.lyrico.ui.components.blur.BlurredTopBar
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.ui.components.scaffoldContentPadding
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.viewmodel.ArtistPosterFoldersUiState
import com.lonx.lyrico.viewmodel.ArtistPosterFoldersViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AddFolder
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 艺术家海报文件夹的设置与浏览页。
 *
 * 与 Android 的差别集中在一处权限模型上：
 *
 * * **`OpenDocumentTree` + `takePersistableUriPermission`** 换成 [rememberDirectoryPicker]。Windows
 *   的目录不需要授权也不需要把授权持久化，所以那一整段（连同它的 `SecurityException` 兜底）没有了；
 *   用户取消对话框就是唯一的退出方式，`addFolder` 收的也是绝对路径。
 * * **`LocalContext`** 随之消失（它只服务于上面那段和 `Toast`）。
 * * **`MarkdownText`** 换成 [MiuixMarkdownText]：`com.github.jeziellago:compose-markdown` 是 Android
 *   专属库，桌面端用的是多平台渲染器。两个已知缺口（图片不渲染、链接不可点）记在
 *   `MiuixMarkdown.kt` 和 `PLAN.md` 里。
 *
 * 帮助文案本身没动：`artist_poster_folders_help` 里仍然写着支持 MP4，而桌面端**不能播放**视频海报，
 * 只能显示它内嵌的封面（`readPosterBytes` 的 KDoc 与 `PLAN.md` 记了这条缺口）。
 */
@Composable
fun ArtistPosterFoldersScreen(navigator: Navigator) {
    val viewModel: ArtistPosterFoldersViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val folderPicker = rememberDirectoryPicker(
        title = stringResource(Res.string.artist_poster_folder_add)
    )

    // 桌面端没有回调式文件选择器：两个入口都是挂起调用，选完直接投给 ViewModel。
    fun chooseFolder() {
        scope.launch {
            val folder = folderPicker.pick() ?: return@launch
            viewModel.addFolder(folder.absolutePath)
        }
    }

    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()
    val topBarBackdrop = rememberBarBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredTopBar(backdrop = topBarBackdrop) {
                SmallTopAppBar(
                    title = stringResource(Res.string.artist_poster_folders),
                    color = Color.Transparent,
                    defaultWindowInsetsPadding = false,
                    navigationIcon = {
                        IconButton(onClick = { navigator.popBackStack() }) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(Res.string.artist_poster_back)
                            )
                        }
                    },
                    actions = {
                        if (state.folder == null) {
                            IconButton(onClick = { chooseFolder() }) {
                                Icon(
                                    imageVector = MiuixIcons.AddFolder,
                                    contentDescription = stringResource(Res.string.artist_poster_folder_add)
                                )
                            }
                        }
                        IconButton(onClick = { showHelp = true }) {
                            Icon(
                                imageVector = MiuixIcons.Info,
                                contentDescription = stringResource(Res.string.artist_poster_naming)
                            )
                        }
                        if (state.folder != null) {
                            OverlayIconDropdownMenu(
                                entries = listOf(
                                    DropdownEntry(
                                        items = listOf(
                                            DropdownItem(
                                                text = stringResource(Res.string.action_refresh_folder),
                                                onClick = viewModel::refresh
                                            ),
                                            DropdownItem(
                                                text = stringResource(Res.string.artist_poster_folder_change),
                                                onClick = { chooseFolder() }
                                            ),
                                            DropdownItem(
                                                text = stringResource(Res.string.folder_action_remove),
                                                onClick = { showRemoveDialog = true }
                                            )
                                        )
                                    )
                                ),
                                enabled = !state.isLoading
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.More,
                                    contentDescription = stringResource(Res.string.cd_more_actions)
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blurSource(topBarBackdrop)
        ) {
            val folder = state.folder
            if (folder != null) {
                ArtistPosterFolderContents(
                    folder = folder,
                    revision = state.revision,
                    isLoading = state.isLoading,
                    hasError = state.error,
                    padding = scaffoldContentPadding(paddingValues),
                    scrollBehavior = scrollBehavior
                )
            } else {
                ArtistPosterFolderEmptyState(
                    state = state,
                    onChooseFolder = { chooseFolder() },
                    padding = scaffoldContentPadding(paddingValues),
                    scrollBehavior = scrollBehavior
                )
            }
        }
        WindowDialog(
            title = stringResource(Res.string.artist_poster_naming),
            show = showHelp,
            onDismissRequest = { showHelp = false }
        ) {
            MiuixMarkdownText(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                markdown = stringResource(Res.string.artist_poster_folders_help)
            )
        }
        val folder = state.folder
        if (showRemoveDialog && folder != null) {
            WindowDialog(
                title = stringResource(Res.string.dialog_remove_folder_title),
                show = true,
                onDismissRequest = { showRemoveDialog = false }
            ) {
                Column {
                    Text(folder.path, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(Res.string.artist_poster_folder_remove_tip),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.body2.fontSize
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            text = stringResource(Res.string.cancel),
                            onClick = { showRemoveDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(
                            text = stringResource(Res.string.confirm),
                            onClick = {
                                viewModel.removeFolder()
                                showRemoveDialog = false
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistPosterFolderEmptyState(
    state: ArtistPosterFoldersUiState,
    onChooseFolder: () -> Unit,
    padding: PaddingValues,
    scrollBehavior: ScrollBehavior
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = padding,
        overscrollEffect = null
    ) {
        item {
            Text(
                stringResource(Res.string.artist_poster_folders_summary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)
            )
        }
        if (state.error) {
            item {
                Text(
                    stringResource(Res.string.artist_poster_folder_error),
                    color = MiuixTheme.colorScheme.error,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        if (state.isLoading && state.folder == null) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )
            }
        } else if (state.folder == null) {
            item {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    BasicComponent(
                        title = stringResource(Res.string.artist_poster_folder_add),
                        summary = stringResource(Res.string.artist_poster_folders_empty),
                        onClick = onChooseFolder
                    )
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
