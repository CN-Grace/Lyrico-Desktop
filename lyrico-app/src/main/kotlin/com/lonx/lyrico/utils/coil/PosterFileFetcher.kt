package com.lonx.lyrico.utils.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.lonx.lyrico.viewmodel.ArtistPosterFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * 画海报文件夹里的文件，包括 `.mp4` 海报。
 *
 * 普通图片本来交给 Coil 内置的 `File` 解码器就行，这个 fetcher 存在的唯一理由是视频海报：Windows
 * 上没有 `MediaMetadataRetriever`，[readPosterBytes] 改用文件里内嵌的封面图，所以 `.mp4` 只能经由
 * 这里出图。Android 那版把 `content://` uri 换回 `InputStream`，桌面端直接读文件。
 *
 * 解析失败（包括没有内嵌封面的视频）返回 `null`：Coil 就当作加载失败，界面显示
 * `artist_poster_preview_error`，与 Android 一致。
 */
class PosterFileFetcherFactory : Fetcher.Factory<ArtistPosterFile> {
    override fun create(data: ArtistPosterFile, options: Options, imageLoader: ImageLoader): Fetcher =
        object : Fetcher {
            override suspend fun fetch() = withContext(Dispatchers.IO) {
                val bytes = readPosterBytes(File(data.path), data.name)
                    ?: return@withContext null
                SourceFetchResult(
                    source = ImageSource(Buffer().write(bytes), options.fileSystem),
                    mimeType = "image/*",
                    dataSource = DataSource.DISK
                )
            }
        }
}
