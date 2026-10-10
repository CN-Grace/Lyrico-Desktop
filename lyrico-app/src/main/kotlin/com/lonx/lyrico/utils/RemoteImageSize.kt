package com.lonx.lyrico.utils

import com.lonx.lyrico.utils.logging.PlatformLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

private const val TAG = "RemoteImageSize"

/** How long to wait for a cover host before giving up, in milliseconds. */
private const val CONNECT_TIMEOUT_MILLIS = 5_000
private const val READ_TIMEOUT_MILLIS = 5_000

/**
 * Reads the pixel dimensions of a remote image without decoding the whole picture.
 *
 * The search pages show "`1200×1200`" over a cover so the user can tell a thumbnail from a full-size
 * scan before applying it. Android did that with `BitmapFactory.Options(inJustDecodeBounds = true)`,
 * which reads the header and stops. There is no `BitmapFactory` on the desktop; the JDK's
 * `ImageIO.getImageReaders` is the same shape of API -- an [ImageReader] answers [ImageReader.getWidth]
 * and [ImageReader.getHeight] from the header alone, so a 40 MB PNG costs a few hundred bytes of
 * traffic rather than a 40 MB decode.
 *
 * Returns `null` for anything that is not a readable image (a 404 body, an HTML error page, a host
 * that does not answer), because the caller's contract is "show the number if we have one": a failed
 * probe must leave the cover showing rather than fail the search. Android failed this call with
 * `printStackTrace()`; here it is a `PlatformLog` warning, which is observable in the app log. Both
 * ways of failing are logged -- an unreadable body and a dead connection alike -- so that a cover that
 * never shows its size can be told apart from one still being probed.
 *
 * Two deliberate differences from the Android version:
 *
 * * **The connect and read timeouts are explicit.** `URL.openStream()` inherits the JVM's defaults,
 *   which are *infinite* -- a cover host that accepts the connection and then stalls would hang this
 *   coroutine for the life of the process. The probe is best-effort decoration, so it gives up after
 *   [CONNECT_TIMEOUT_MILLIS] / [READ_TIMEOUT_MILLIS].
 * * **The reader is disposed and the stream closed.** `BitmapFactory.decodeStream` never closed the
 *   stream it was handed (it does not own it); here the connection is opened by this function, so it
 *   owns it, and a cover grid that probes 40 images must not hold 40 sockets open.
 */
suspend fun readRemoteImageSize(url: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
    var stream: ImageInputStream? = null
    var reader: ImageReader? = null
    try {
        val connection = URL(url).openConnection().apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
        }
        stream = ImageIO.createImageInputStream(connection.getInputStream())
        val readers = stream?.let { ImageIO.getImageReaders(it) }
        if (readers == null || !readers.hasNext()) {
            // The response arrived but carries no image the JDK can read: an HTML error page, a
            // rate-limit JSON body, a truncated file. That is not an exception, but it is not silent
            // either -- a cover with no size badge looks identical to one whose probe is still running,
            // so the reason is written where the app log can show it. Same wording as the failure
            // below: to the reader of the log the cause is the same, "this URL gave no size".
            PlatformLog.w(TAG, "无法读取图片尺寸: $url")
            return@withContext null
        }

        reader = readers.next()
        reader.input = stream
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        if (width > 0 && height > 0) width to height else null
    } catch (error: Exception) {
        // Not an error the user needs to see: an unreadable cover is a cover without a size badge.
        PlatformLog.w(TAG, "无法读取图片尺寸: $url", error)
        null
    } finally {
        reader?.dispose()
        runCatching { stream?.close() }
    }
}
