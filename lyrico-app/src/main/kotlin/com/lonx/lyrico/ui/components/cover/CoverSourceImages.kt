package com.lonx.lyrico.ui.components.cover

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.lonx.lyrico.ui.components.CoverRequest
import com.lonx.lyrico.utils.CoverSourceType
import com.lonx.lyrico.utils.getCoverSourceType
import com.lonx.lyrico.utils.logging.PlatformLog
import com.lonx.lyrico.utils.readLocalImageSize
import com.lonx.lyrico.utils.readRemoteImageSize
import com.lonx.lyrico.utils.coil.readExternalArtistPoster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import javax.imageio.ImageIO

private const val TAG = "CoverSourceImage"

/**
 * The three things a screen can do with a cover *source* -- draw it, measure it, decode it -- in one
 * place, because on Android they were all reached through `Context` and here they are not.
 *
 * The Android editor called `getBitmap(context, data)` (`utils/PainterUtils`-adjacent) whenever it
 * needed pixels: a `Uri` went through `ImageDecoder` and the `ContentResolver`, which is the whole
 * reason a composable needed a `Context` at all. Desktop sources carry a `ByteArray`, an absolute
 * Windows path, an `http(s)` URL or an [ImageBitmap] (`utils/CoverSourceType.kt` classifies them), and
 * every one of those decodes with the JDK, so the composable needs no platform object.
 *
 * This file replaces, in the editor's two call sites, exactly this much of the Android code:
 *
 * * `getBitmap(context, target.data)` -> [decodeCoverBitmap]
 * * `BitmapFactory.Options(inJustDecodeBounds = true)` + `context.contentResolver.openInputStream` ->
 *   [readCoverSourceSize] (delegating to `utils/LocalImageSize.kt` / `utils/RemoteImageSize.kt`)
 * * `AsyncImage(model = ...)` -> [CoverSourceImage]
 *
 * Everything else the editor knew about images -- cropping, encoding, cache keys -- already lives
 * behind the view model (`EditMetadataViewModel`), so nothing here writes a file or touches the DB.
 */

/**
 * The source's pixels, or `null` if they cannot be read.
 *
 * [CoverSourceType.NETWORK_URL] is fetched with `URL.openStream()` -- the same blocking read Android
 * did after the resolver handed it the bytes -- so call this from a background dispatcher; both call
 * sites in the editor already ran on `Dispatchers.IO`. It is deliberately *not* suspend: the cropper
 * wants the bitmap synchronously once it has it, and a suspend variant would only invite callers to
 * assume the UI thread is safe.
 *
 * An [ImageBitmap] source is returned as-is (Android did the same for a `Bitmap`), a path or URL is
 * read and decoded, and an unreadable source is `null` rather than an exception: Android's
 * `getBitmap` caught everything, printed it, and returned `null`, and the caller's `?.let {}` shape is
 * what keeps the editor from crashing on a corrupt cover. The failure is logged through
 * [PlatformLog] instead of a bare stack trace so it shows up in the app log.
 */
fun decodeCoverBitmap(source: Any?): ImageBitmap? = try {
    when (getCoverSourceType(source)) {
        CoverSourceType.BYTE_ARRAY -> decodeImageBytes(source as ByteArray)

        CoverSourceType.IMAGE_BITMAP -> source as ImageBitmap

        CoverSourceType.FILE_PATH -> decodeImageBytes(File(source.toString().trim()).readBytes())

        CoverSourceType.NETWORK_URL -> URL(source.toString().trim()).openStream()
            .use { stream -> decodeImageBytes(stream.readBytes()) }

        CoverSourceType.UNSUPPORTED -> null
    }
} catch (error: Exception) {
    PlatformLog.w(TAG, "无法解码封面来源: $source", error)
    null
}

/**
 * `1000×1000` for the badge the editor draws over a cover, or `null` when it cannot be determined.
 *
 * Only the header is read ([readLocalImageSize]), never the pixels, except for an [ImageBitmap] whose
 * size is already known and a [CoverRequest] whose poster file has to be located first. A
 * [CoverRequest] is the one case where the UI holds no bytes at all -- the poster lives in a folder
 * next to the media and is resolved by name -- so it reads the very bytes the cover fetcher resolves,
 * through the same reader, and measures those; the editor then shows the size of the picture it is
 * actually displaying instead of a second, differently-chosen one.
 *
 * Reads block, so it dispatches to `Dispatchers.IO` itself and callers can await it straight from a
 * `LaunchedEffect`.
 */
suspend fun readCoverSourceSize(source: Any?): Pair<Int, Int>? {
    if (source is CoverRequest) {
        val bytes = withContext(Dispatchers.IO) {
            readExternalArtistPoster(source.artistName, source.artistPosterFolders)
        }
        return bytes?.takeIf { it.isNotEmpty() }?.let { readLocalImageSize(it) }
    }

    return withContext(Dispatchers.IO) {
        when (getCoverSourceType(source)) {
            CoverSourceType.BYTE_ARRAY -> readLocalImageSize(source as ByteArray)

            CoverSourceType.IMAGE_BITMAP -> (source as ImageBitmap).let { it.width to it.height }

            CoverSourceType.FILE_PATH -> readLocalImageSize(File(source.toString().trim()))

            CoverSourceType.NETWORK_URL -> readRemoteImageSize(source.toString().trim())

            CoverSourceType.UNSUPPORTED -> null
        }
    }
}

/**
 * Draws a cover source of any kind.
 *
 * The branch is the whole reason this composable exists: Coil has no model for a Compose
 * [ImageBitmap] on the desktop -- the app's own loader answers `ErrorResult` for one, which
 * `CoverDisplayModelTest` pins down -- so a bitmap the user just picked or cropped is drawn with
 * [Image]. Every other source keeps going through [AsyncImage], exactly as on Android: the embedded
 * `ByteArray` is served by the default registry's `ByteArrayFetcher`, an absolute Windows path by its
 * `PathMapper`, an `http(s)` URL by the network fetcher, and a [CoverRequest] by this app's own
 * fetcher/keyer pair. Those four were verified against the app's real `buildImageLoader` rather than
 * assumed, because "Coil can load a path string" is the kind of thing that silently stops being true.
 *
 * Never hand this a `file://` URI: the registry has no mapper for that scheme and the load fails --
 * sources are either real paths or bytes.
 */
@Composable
fun CoverSourceImage(
    source: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Painter? = null,
    error: Painter? = null
) {
    val bitmap = source as? ImageBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        AsyncImage(
            model = source,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            placeholder = placeholder,
            error = error
        )
    }
}

private fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
