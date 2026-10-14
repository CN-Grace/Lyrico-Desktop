package com.lonx.lyrico.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.lyrico.data.model.entity.AlbumEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.platform.rememberDirectoryPicker
import com.lonx.lyrico.platform.rememberFileOpenPicker
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.album_song_count
import com.lonx.lyrico.resources.artist_detail_title
import com.lonx.lyrico.resources.artist_poster_folder_add
import com.lonx.lyrico.resources.artist_tab_albums
import com.lonx.lyrico.resources.artist_tab_songs
import com.lonx.lyrico.resources.dialog_delete_album_content
import com.lonx.lyrico.resources.dialog_delete_album_title
import com.lonx.lyrico.resources.label_set_artist_poster
import com.lonx.lyrico.resources.msg_copied_to_clipboard
import com.lonx.lyrico.resources.swipe_selection_enter_selection
import com.lonx.lyrico.resources.swipe_selection_range_end
import com.lonx.lyrico.resources.swipe_selection_range_start
import com.lonx.lyrico.ui.components.CoverCandidate
import com.lonx.lyrico.ui.components.album.AlbumListItem
import com.lonx.lyrico.ui.components.bar.SongBatchSelectionActions
import com.lonx.lyrico.ui.components.bar.SongSelectionTopAppBar
import com.lonx.lyrico.ui.components.base.YesNoDialog
import com.lonx.lyrico.ui.components.cover.CoverImage
import com.lonx.lyrico.ui.components.library.AlbumActionBottomSheet
import com.lonx.lyrico.ui.components.library.AlbumReplayGainProgressBottomSheet
import com.lonx.lyrico.ui.components.poster.ArtistPosterActionsSheet
import com.lonx.lyrico.ui.components.poster.ArtistPosterProgressSheet
import com.lonx.lyrico.ui.components.scaffoldTopHorizontalPadding
import com.lonx.lyrico.ui.components.song.SongActionSheets
import com.lonx.lyrico.ui.components.song.SongListItem
import com.lonx.lyrico.ui.components.song.SongListItemActions
import com.lonx.lyrico.ui.navigation.AlbumDetailDestination
import com.lonx.lyrico.ui.navigation.EditMetadataDestination
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.AlbumActionsViewModel
import com.lonx.lyrico.viewmodel.ArtistDetailViewModel
import com.lonx.lyrico.viewmodel.SongSelectionViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.awt.datatransfer.StringSelection
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * The artist detail page: the artist's round poster, name and counts in a header, over a two-tab pager
 * holding the artist's songs and the artist's albums.
 *
 * The port keeps Android's structure and every user-visible string. Six Android mechanisms are gone
 * rather than shimmed:
 *
 * * **`Toast`** became the Miuix [SnackbarHost] the other ported pages use. There were three of them:
 *   the "poster folder added" failure, the "couldn't read the picked poster" report, and the poster
 *   write's own result. The last one is `ArtistPosterEmbedUiState.message`, which is still what feeds
 *   the progress sheet; the snackbar path is the same one Android took (a message that arrives while
 *   the sheet is *not* up is reported as a transient toast), resolved through `UiMessage.resolve()`.
 * * **`LocalContext`**, which existed only to build those `Toast`s and to hand `Context` to the view
 *   model's `prepareArtistImage`/`setArtistPosterFolder`. Neither needs one on the desktop.
 * * **`rememberLauncherForActivityResult`** twice over: `PickVisualMedia` (image picking) is
 *   `rememberFileOpenPicker`, and `OpenDocumentTree` (the poster folder) is `rememberDirectoryPicker`.
 *   Both are suspending, so the "pick a folder, then pick an image" chain is a coroutine that awaits
 *   the two dialogs in order instead of threading a `pendingArtistPosterTarget` through two result
 *   callbacks.
 * * **`takePersistableUriPermission`** and its `permission_denied_cannot_save` report. A Windows
 *   directory needs no grant, so a cancelled folder dialog is the only way out of that step and it is
 *   silent -- exactly as Android treated a `null` tree URI.
 * * **`StartIntentSenderForResult`** and the `permissionIntentSender` round trip: the desktop write
 *   result has no `PermissionRequired` case at all (`SaveAudioTagsResult` documents why), so there is
 *   nothing to ask the user for and the progress sheet lost its permission button with it.
 * * **`BackHandler`.** Android used it to close the batch FAB menu and otherwise to leave selection
 *   mode. Compose Desktop has no system back gesture -- the same handler is dropped on the other
 *   ported pages for the same reason -- so the FAB menu closes by tapping the scrim and
 *   `SongSelectionTopAppBar`'s close button is how a user leaves selection mode. `PLAN.md` records the
 *   resulting UX gap.
 *
 * Two call sites differ because the components they call were ported earlier:
 *
 * * `SongActionSheets` takes `onShare` and `onCopy` (Android derived both from the `Context`), so share
 *   is "reveal in Explorer" through `SongSelectionViewModel.share` and copy goes to the AWT-backed
 *   `LocalClipboard`, as on the album detail page.
 * * `CoverCandidate.uri` is the song's path, not a `content://` URI, so no `toUri()` conversion.
 *
 * One deliberate addition, and it is the only behaviour change: **the album ReplayGain progress sheet
 * is shown.** Android's artist page started a ReplayGain run from its album action sheet but never
 * showed `AlbumReplayGainProgressBottomSheet`, so the run was invisible and un-abortable there (the
 * albums page, which owns the same `AlbumActionsViewModel` call, did show it). The desktop page wires
 * the same sheet and collects the same `events` flow the albums page does, so the action the user just
 * took reports itself instead of leaving one greyed-out row behind.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ArtistDetailScreen(
    navigator: Navigator,
    artistId: Long
) {
    val viewModel: ArtistDetailViewModel = koinViewModel(
        parameters = { parametersOf(artistId) }
    )
    val selectionViewModel: SongSelectionViewModel = koinViewModel()
    val artist by viewModel.artist.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()

    var selectedAlbum by remember { mutableStateOf<AlbumEntity?>(null) }
    val albumActionsViewModel: AlbumActionsViewModel = koinViewModel()
    val albumActionsUiState by albumActionsViewModel.uiState.collectAsStateWithLifecycle()

    val artistName = artist?.name.orEmpty()
    val isSelectionMode by selectionViewModel.isSelectionMode.collectAsStateWithLifecycle()
    val selectedSongUris by selectionViewModel.selectedSongUris.collectAsStateWithLifecycle()
    val swipeAnchorUri by selectionViewModel.swipeAnchorUri.collectAsStateWithLifecycle()

    var showAlbumActionSheet by remember { mutableStateOf(false) }
    var showDeleteAlbumDialog by remember { mutableStateOf(false) }
    val swipeSelectionLabel = stringResource(
        if (!isSelectionMode) {
            Res.string.swipe_selection_enter_selection
        } else if (swipeAnchorUri == null) {
            Res.string.swipe_selection_range_start
        } else {
            Res.string.swipe_selection_range_end
        }
    )
    val swipeSelectionSecondaryLabel = if (!isSelectionMode) {
        stringResource(Res.string.swipe_selection_range_start)
    } else {
        null
    }
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })
    var isFabMenuExpanded by remember { mutableStateOf(false) }

    var selectedSong by remember { mutableStateOf<SongEntity?>(null) }
    var showMenuSheet by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboard.current
    val copiedMessage = stringResource(Res.string.msg_copied_to_clipboard)

    // Publishes what this list shows so the window-level ctrl+A selects these songs and not the whole
    // library; see SharedSelectionManager.publishVisibleUris.
    LaunchedEffect(songs) {
        selectionViewModel.publishVisibleSongs(songs)
    }

    // 头像只是设置入口：用户选好去向后，再从本地选一张图片。
    val posterFolder by viewModel.artistPosterFolder.collectAsStateWithLifecycle()
    val artistPosterState by viewModel.artistPosterEmbedState.collectAsStateWithLifecycle()
    var showArtistPosterSheet by remember { mutableStateOf(false) }
    var showArtistPosterProgress by remember { mutableStateOf(false) }

    // 桌面端没有 ActivityResult 回调：两个对话框都是挂起的，链式流程写成顺序的协程。
    // 扩展名列表与单曲编辑页的取图入口一致（`EditMetadataScreen` 里那份是私有的）。
    val artistImagePicker = rememberFileOpenPicker(
        title = stringResource(Res.string.label_set_artist_poster),
        extensions = LocalImageExtensions
    )
    val posterFolderPicker = rememberDirectoryPicker(
        title = stringResource(Res.string.artist_poster_folder_add)
    )

    /**
     * 选一张本地图片，并按 [target] 交给 ViewModel 的内嵌或外置路径。
     *
     * Android 把「去哪个选项」记在 `pendingArtistPosterTarget` 里等回调读；这里 `pick()` 直接
     * 返回结果，去向跟着闭包走，不会在重组之间过期。
     */
    fun startArtistImagePick(target: ArtistPosterTarget) {
        scope.launch {
            val file = artistImagePicker.pick() ?: return@launch
            viewModel.prepareArtistImage(file) { source ->
                showArtistPosterProgress = true
                when (target) {
                    ArtistPosterTarget.EmbedIntoSongs ->
                        viewModel.embedArtistImageForAllSongs(artistName, songs, source)

                    ArtistPosterTarget.SaveToFolder ->
                        viewModel.saveArtistImageToFolder(artistName, source)
                }
            }
        }
    }

    // Android 用 `Toast` 报这一条；面板没开时才提示，否则结论已经在面板里了。
    LaunchedEffect(artistPosterState.message, showArtistPosterProgress) {
        val message = artistPosterState.message
        if (message != null && !showArtistPosterProgress) {
            message.resolve()?.let { text -> snackbarHostState.showSnackbar(text) }
            viewModel.clearArtistPosterMessage()
        }
    }

    // 专辑动作的结论从这里出（Android 在专辑页才收集，见类注释里的那条改动）。
    LaunchedEffect(albumActionsViewModel) {
        albumActionsViewModel.events.collect { message ->
            message.resolve()?.let { text -> snackbarHostState.showSnackbar(text) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                AnimatedContent(
                    targetState = isSelectionMode,
                    label = "ArtistDetailTopBarAnimation",
                    transitionSpec = {
                        val animationDuration = 300
                        val enter = fadeIn(tween(animationDuration)) +
                                slideInVertically(
                                    animationSpec = tween(
                                        animationDuration,
                                        easing = FastOutSlowInEasing
                                    ),
                                    initialOffsetY = { -it / 3 }
                                )
                        val exit = fadeOut(tween(animationDuration)) +
                                slideOutVertically(
                                    animationSpec = tween(
                                        animationDuration,
                                        easing = FastOutSlowInEasing
                                    ),
                                    targetOffsetY = { -it / 3 }
                                )

                        (enter togetherWith exit).using(SizeTransform(clip = false))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { selectionMode ->
                    if (selectionMode) {
                        SongSelectionTopAppBar(
                            songs = songs,
                            selectedSongUris = selectedSongUris,
                            scrollBehavior = topAppBarScrollBehavior,
                            onSelectAll = selectionViewModel::selectAll,
                            onDeselectAll = selectionViewModel::deselectAll,
                            onClose = selectionViewModel::exitSelectionMode
                        )
                    } else {
                        SmallTopAppBar(
                            title = artistName.ifBlank {
                                stringResource(Res.string.artist_detail_title)
                            },
                            navigationIcon = {
                                IconButton(onClick = { navigator.popBackStack() }) {
                                    Icon(
                                        imageVector = MiuixIcons.Back,
                                        contentDescription = stringResource(Res.string.action_back)
                                    )
                                }
                            },
                            scrollBehavior = topAppBarScrollBehavior
                        )
                    }
                }
            }
        ) { paddingValues ->
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scaffoldTopHorizontalPadding(paddingValues))
                ) {
                    ArtistDetailHeader(
                        artist = artistName,
                        songCount = songs.size,
                        albums = albums,
                        coverUri = artist?.coverSongUri,
                        coverLastModified = artist?.coverSongLastModified ?: 0L,
                        coverCandidates = songs.map { song ->
                            CoverCandidate(
                                uri = song.uri,
                                lastUpdate = song.fileLastModified
                            )
                        },
                        onAvatarClick = { showArtistPosterSheet = true }
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp)
                    ) {
                        TabRowWithContour(
                            tabs = listOf(
                                stringResource(Res.string.artist_tab_songs),
                                stringResource(Res.string.artist_tab_albums)
                            ),
                            selectedTabIndex = pagerState.currentPage,
                            onTabSelected = { index ->
                                scope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) { page ->
                        if (page == 0) {
                            ArtistSongsPage(
                                songs = songs,
                                isSelectionMode = isSelectionMode,
                                selectedSongUris = selectedSongUris,
                                swipeSelectionLabel = swipeSelectionLabel,
                                swipeSelectionSecondaryLabel = swipeSelectionSecondaryLabel,
                                topAppBarScrollBehavior = topAppBarScrollBehavior,
                                onSongClick = { song ->
                                    selectionViewModel.exitSelectionMode()
                                    navigator.navigate(EditMetadataDestination(songFileUri = song.uri))
                                },
                                onToggleSelection = { song ->
                                    selectionViewModel.toggleSelection(song.uri)
                                },
                                onSwipeSelection = { song ->
                                    selectionViewModel.swipeSelect(song, songs)
                                },
                                onRangeSelection = { song ->
                                    selectionViewModel.extendSelection(song, songs)
                                },
                                onShowSongMenu = { song ->
                                    selectedSong = song
                                    showMenuSheet = true
                                }
                            )
                        } else {
                            ArtistAlbumsPage(
                                albums = albums,
                                topAppBarScrollBehavior = topAppBarScrollBehavior,
                                onAlbumClick = { album ->
                                    selectionViewModel.exitSelectionMode()
                                    navigator.navigate(
                                        AlbumDetailDestination(albumId = album.id)
                                    )
                                },
                                onAlbumActionClick = { album ->
                                    selectedAlbum = album
                                    showAlbumActionSheet = true
                                }
                            )
                        }
                    }
                }

                SongActionSheets(
                    selectedSong = selectedSong,
                    showMenuSheet = showMenuSheet,
                    showDetailSheet = showDetailSheet,
                    showDeleteDialog = showDeleteDialog,
                    showRenameDialog = showRenameDialog,
                    onDismissMenu = { showMenuSheet = false },
                    onDismissMenuFinished = { selectedSong = null },
                    onDismissDetail = { showDetailSheet = false },
                    onDismissDelete = { showDeleteDialog = false },
                    onDismissRename = { showRenameDialog = false },
                    onShowDetail = { showDetailSheet = true },
                    onShowDelete = { showDeleteDialog = true },
                    onShowRename = { showRenameDialog = true },
                    onPlay = { song -> selectionViewModel.play(song) },
                    onShare = { song -> selectionViewModel.revealSong(song) },
                    onCopy = { text ->
                        scope.launch {
                            // The same AWT-backed clipboard call the other pages make; in a headless test
                            // Compose resolves `LocalClipboard` to a no-op, so it stays safe.
                            clipboardManager.setClipEntry(ClipEntry(StringSelection(text)))
                            snackbarHostState.showSnackbar(copiedMessage)
                        }
                    },
                    onDelete = { song -> selectionViewModel.delete(song) },
                    onRename = { song, newFileName ->
                        selectionViewModel.renameSong(song, newFileName)
                    }
                )
            }
        }
        selectedAlbum?.let { album ->
            AlbumActionBottomSheet(
                show = showAlbumActionSheet,
                albumName = album.name,
                isCalculatingReplayGain = albumActionsUiState.isCalculatingAlbumReplayGain,
                onDismissRequest = { showAlbumActionSheet = false },
                onDismissFinished = {
                    if (!showAlbumActionSheet && !showDeleteAlbumDialog) {
                        selectedAlbum = null
                    }
                },
                onShare = {
                    showAlbumActionSheet = false
                    albumActionsViewModel.shareAlbum(album.id)
                },
                onDelete = {
                    showAlbumActionSheet = false
                    showDeleteAlbumDialog = true
                },
                onCalculateReplayGain = {
                    showAlbumActionSheet = false
                    albumActionsViewModel.calculateAlbumReplayGain(album.id)
                }
            )

            YesNoDialog(
                title = stringResource(Res.string.dialog_delete_album_title),
                show = showDeleteAlbumDialog,
                summary = formattedStringResource(
                    Res.string.dialog_delete_album_content,
                    album.songCount,
                    album.name
                ),
                onConfirm = {
                    showDeleteAlbumDialog = false
                    albumActionsViewModel.deleteAlbum(album.id)
                    selectedAlbum = null
                },
                onDismissRequest = {
                    showDeleteAlbumDialog = false
                    selectedAlbum = null
                }
            )
        }

        // 与专辑页同一个面板、同一份 ViewModel 状态（见类注释里的那条改动）。
        AlbumReplayGainProgressBottomSheet(
            uiState = albumActionsUiState,
            onDismissRequest = albumActionsViewModel::closeAlbumReplayGainProgressDialog,
            onDismissFinished = albumActionsViewModel::clearAlbumReplayGainProgressDialog,
            onAbort = albumActionsViewModel::cancelAlbumReplayGain
        )

        SongBatchSelectionActions(
            navigator = navigator,
            songs = songs,
            show = isSelectionMode,
            expanded = isFabMenuExpanded,
            selectedSongUris = selectedSongUris,
            onExpandedChange = { isFabMenuExpanded = it },
            onSetSelectionUris = selectionViewModel::setSelectionUris,
            onBatchDelete = selectionViewModel::batchDelete,
            // Android's `batchShare`; both Android share paths became the reveal call on desktop.
            onBatchShare = selectionViewModel::share
        )

        // 点头像后选去向：内嵌到该歌手的全部歌曲，或按艺术家名存进海报文件夹
        ArtistPosterActionsSheet(
            show = showArtistPosterSheet,
            hasPosterFolder = posterFolder != null,
            onEmbedToSongs = {
                showArtistPosterSheet = false
                startArtistImagePick(ArtistPosterTarget.EmbedIntoSongs)
            },
            onSaveToFolder = {
                showArtistPosterSheet = false
                if (posterFolder == null) {
                    // 还没有海报文件夹时先让用户选一个，选完再选图片（`setArtistPosterFolder`
                    // 落盘并刷新成功后回调）。
                    scope.launch {
                        val folder = posterFolderPicker.pick() ?: return@launch
                        viewModel.setArtistPosterFolder(folder.absolutePath) {
                            startArtistImagePick(ArtistPosterTarget.SaveToFolder)
                        }
                    }
                } else {
                    startArtistImagePick(ArtistPosterTarget.SaveToFolder)
                }
            },
            onDismissRequest = { showArtistPosterSheet = false }
        )

        ArtistPosterProgressSheet(
            state = artistPosterState,
            show = showArtistPosterProgress && artistPosterState.hasContent,
            title = stringResource(Res.string.label_set_artist_poster),
            onDismissRequest = { showArtistPosterProgress = false },
            onDismissFinished = {
                // 动画走完才丢掉结果，否则面板会先空一下再消失
                if (!artistPosterState.isRunning) {
                    showArtistPosterProgress = false
                    viewModel.clearArtistPosterStatus()
                }
            },
            onCancel = viewModel::cancelArtistPosterWrite
        )
    }
}

/**
 * 本地图片的扩展名，与 `EditMetadataScreen` 的取图入口保持一致。
 *
 * 两处目前各有一份；再来第三处就该提到共享位置了。
 */
private val LocalImageExtensions = listOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

private enum class ArtistPosterTarget {
    EmbedIntoSongs,
    SaveToFolder
}

@Composable
private fun ArtistSongsPage(
    songs: List<SongEntity>,
    isSelectionMode: Boolean,
    selectedSongUris: Set<String>,
    swipeSelectionLabel: String,
    swipeSelectionSecondaryLabel: String?,
    topAppBarScrollBehavior: ScrollBehavior,
    onSongClick: (SongEntity) -> Unit,
    onToggleSelection: (SongEntity) -> Unit,
    onSwipeSelection: (SongEntity) -> Unit,
    onRangeSelection: (SongEntity) -> Unit,
    onShowSongMenu: (SongEntity) -> Unit
) {
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = Modifier
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
            .fillMaxHeight(),
        state = listState,
        contentPadding = PaddingValues(bottom = 12.dp),
        overscrollEffect = null
    ) {
        items(
            items = songs,
            key = { song -> song.uri.takeIf { it.isNotBlank() && it != "0" } ?: "song-${song.id}" }
        ) { song ->
            SongListItem(
                song = song,
                showTrackNumbers = true,
                isSelectionMode = isSelectionMode,
                isSelected = selectedSongUris.contains(song.uri),
                swipeSelectionLabel = swipeSelectionLabel,
                swipeSelectionSecondaryLabel = swipeSelectionSecondaryLabel,
                onClick = { onSongClick(song) },
                onToggleSelection = { onToggleSelection(song) },
                onSwipeSelection = { onSwipeSelection(song) },
                onRangeSelection = { onRangeSelection(song) },
                onSecondaryClick = { onShowSongMenu(song) },
                trailingContent = {
                    Box(modifier = Modifier.padding(end = 8.dp)) {
                        SongListItemActions(
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedSongUris.contains(song.uri),
                            onToggleSelection = { onToggleSelection(song) },
                            onShowMenu = { onShowSongMenu(song) }
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun ArtistAlbumsPage(
    albums: List<AlbumEntity>,
    topAppBarScrollBehavior: ScrollBehavior,
    onAlbumClick: (AlbumEntity) -> Unit,
    onAlbumActionClick: (AlbumEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
            .fillMaxHeight(),
        contentPadding = PaddingValues(bottom = 12.dp),
        overscrollEffect = null
    ) {
        items(
            items = albums,
            key = { album -> album.id }
        ) { album ->
            AlbumListItem(
                album = album,
                onClick = { onAlbumClick(album) },
                trailingContent = {
                    Box(modifier = Modifier.padding(end = 8.dp)) {
                        IconButton(
                            onClick = {
                                onAlbumActionClick(album)
                            }
                        ) {
                            Icon(
                                MiuixIcons.More,
                                contentDescription = "more"
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ArtistDetailHeader(
    artist: String,
    songCount: Int,
    albums: List<AlbumEntity>,
    coverUri: String?,
    coverLastModified: Long,
    coverCandidates: List<CoverCandidate>,
    onAvatarClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图片角标沿用单曲编辑页的图片语义，同时保留整张海报的点击热区。
        Box(contentAlignment = Alignment.BottomEnd) {
            CoverImage(
                uri = coverUri,
                lastModified = coverLastModified,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .clickable(
                        onClickLabel = stringResource(Res.string.label_set_artist_poster),
                        onClick = onAvatarClick
                    ),
                shape = CircleShape,
                contentDescription = artist,
                pictureType = AudioPictureType.Artist,
                fallbackPictureTypes = listOf(
                    AudioPictureType.LeadArtist,
                    AudioPictureType.Band
                ),
                fallbackToAny = true,
                candidates = coverCandidates,
                artistName = artist
            )
            Icon(
                imageVector = MiuixIcons.Image,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.secondaryContainer)
                    .padding(5.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.ifBlank { stringResource(Res.string.artist_detail_title) },
                style = MiuixTheme.textStyles.title3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formattedStringResource(
                    Res.string.album_song_count,
                    albums.size,
                    songCount
                ),
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
