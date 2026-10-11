package com.lonx.lyrico.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.artist_poster_back
import com.lonx.lyrico.resources.artist_poster_file_count
import com.lonx.lyrico.resources.artist_poster_folder_error
import com.lonx.lyrico.resources.artist_poster_folder_unavailable
import com.lonx.lyrico.resources.artist_poster_no_files
import com.lonx.lyrico.resources.artist_poster_preview_error
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.ArtistPosterFile
import com.lonx.lyrico.viewmodel.ArtistPosterFolder
import java.io.File
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 海报文件夹的内容网格，以及点开后的全屏预览。
 *
 * 与 Android 的差别有两处，都跟“桌面端不会播视频”有关：
 *
 * * **`AndroidView` + `VideoView` 没了**：预览页里 `.mp4` 那一支原本是内嵌播放器（`MediaController`
 *   拖动条、`onPrepared` 自动起播、`onRelease` 停播）。Windows 上进程内没有视频解码器，这一支连
 *   同网格缩略图上的 `MP4` 角标一起去掉——角标原本的意思是“点开会播放”，现在点了只会看到内嵌封面，
 *   留着就是骗人。`.mp4` 走 `PosterFileFetcher` 取内嵌封面，取不到就显示 `artist_poster_preview_error`。
 * * **`Dialog` 的属性少一个**：`decorFitsSystemWindows` 是 Android 的窗口装饰概念，桌面端的
 *   `DialogProperties` 没有这个字段（也不是 no-op，概念本身不存在），所以只保留
 *   `usePlatformDefaultWidth = false`；`safeDrawingPadding()` 同理去掉，桌面窗口没有系统栏会盖住内容。
 *
 * 认人仍然按文件名（`ArtistPosterMatcher` 在 ViewModel 里筛过），网格顺序、缓存键的组成、
 * 缩放手势（1x-5x，放大后才能平移）都没变。缩放用的仍是 Android 那个三参数的
 * `rememberTransformableState`；它在新版 Compose 里已标 deprecated，替代版会以捏合点为中心缩放，
 * 那是行为改动，留给清理批次，这里只保留移植当时的语义。
 */
@Composable
internal fun ArtistPosterFolderContents(
    folder: ArtistPosterFolder,
    revision: Long,
    isLoading: Boolean,
    hasError: Boolean,
    padding: PaddingValues,
    scrollBehavior: ScrollBehavior
) {
    val files = folder.posters.orEmpty()
    var previewPath by remember(folder.path) { mutableStateOf<String?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        contentPadding = padding,
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        overscrollEffect = null
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        folder.path,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formattedStringResource(Res.string.artist_poster_file_count, files.size),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.body2.fontSize
                    )
                }
            }
        }
        if (isLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )
            }
        }
        if (hasError) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(Res.string.artist_poster_folder_error),
                    color = MiuixTheme.colorScheme.error,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                )
            }
        }
        if (files.isEmpty() && !isLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(
                        if (folder.posters == null) {
                            Res.string.artist_poster_folder_unavailable
                        } else {
                            Res.string.artist_poster_no_files
                        }
                    ),
                    modifier = Modifier.padding(28.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        items(files, key = { it.path }) { file ->
            Column(
                Modifier
                    .padding(horizontal = 6.dp, vertical = 8.dp)
                    .clickable { previewPath = file.path }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    PosterImage(file, revision, Modifier.fillMaxSize(), ContentScale.Crop)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    file.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
    val previewIndex = files.indexOfFirst { it.path == previewPath }
    if (previewIndex >= 0) {
        PosterPreview(files, previewIndex, revision) { previewPath = null }
    }
}

@Composable
private fun PosterImage(
    file: ArtistPosterFile,
    revision: Long,
    modifier: Modifier,
    scale: ContentScale
) {
    // `ImageRequest` 的构建要一个 `PlatformContext`；单例就是桌面端的那个（Android 用的是
    // `LocalContext`，那是 Activity 的 context）。
    val request = remember(file, revision) {
        ImageRequest.Builder(PlatformContext.INSTANCE)
            // 视频海报交给 `PosterFileFetcherFactory` 取内嵌封面，普通图片走 Coil 的文件解码器。
            .data(if (file.name.endsWith(".mp4", true)) file else File(file.path))
            .memoryCacheKey("poster:${file.path}:${file.lastModified}:$revision")
            .diskCacheKey("poster:${file.path}:${file.lastModified}:$revision")
            .build()
    }
    var failed by remember(file, revision) { mutableStateOf(false) }
    var loading by remember(file, revision) { mutableStateOf(true) }
    Box(modifier.background(MiuixTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = file.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = scale,
            onSuccess = {
                loading = false
                failed = false
            },
            onError = {
                loading = false
                failed = true
            }
        )
        if (loading) CircularProgressIndicator(size = 24.dp)
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(MiuixIcons.Image, null)
                Text(
                    stringResource(Res.string.artist_poster_preview_error),
                    fontSize = MiuixTheme.textStyles.body2.fontSize
                )
            }
        }
    }
}

@Composable
private fun PosterPreview(
    files: List<ArtistPosterFile>,
    initialPage: Int,
    revision: Long,
    onDismiss: () -> Unit
) {
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { files.size })
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(
                Modifier.fillMaxWidth().padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = stringResource(Res.string.artist_poster_back),
                        tint = Color.White
                    )
                }
                Text(
                    files[pager.currentPage].name,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${pager.currentPage + 1} / ${files.size}",
                    color = Color.White,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().weight(1f),
                key = { files[it].path }
            ) { page ->
                val file = files[page]
                var zoom by remember(file) { mutableFloatStateOf(1f) }
                var offset by remember(file) { mutableStateOf(Offset.Zero) }
                val transform = rememberTransformableState { zoomChange, pan, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                    offset = if (zoom > 1f) offset + pan else Offset.Zero
                }
                Box(Modifier.fillMaxSize()) {
                    PosterImage(
                        file,
                        revision,
                        Modifier
                            .fillMaxSize()
                            .transformable(transform, canPan = { zoom > 1f })
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = offset.x
                                translationY = offset.y
                            },
                        ContentScale.Fit
                    )
                }
            }
        }
    }
}
