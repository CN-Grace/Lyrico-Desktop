package com.lonx.lyrico.utils

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

/**
 * Pixel dimensions of an image the process already has in hand: an embedded picture decoded out of the
 * tags, a poster file from an artist folder, a cover file on disk.
 *
 * The local sibling of [readRemoteImageSize], and the desktop replacement for the
 * `BitmapFactory.Options(inJustDecodeBounds = true)` calls the editor made to print "`1000×1000`" next
 * to a cover. Same trick as the remote one -- an [ImageReader] answers [ImageReader.getWidth] and
 * [ImageReader.getHeight] from the header alone, so measuring a 40 MB scan costs a few hundred bytes
 * of reading rather than a 40 MB decode. That matters here because this runs once per pager page while
 * the cropper is also holding the full-size bitmap.
 *
 * It is a separate function rather than a shared one because the two contracts genuinely differ. A
 * remote probe is best-effort decoration whose failure has to be explained (see [readRemoteImageSize]:
 * timeouts, `PlatformLog`); a local file that yields no size is not news -- the user is looking at that
 * exact file, and Android's `decodeFile`/`decodeByteArray` returned `null` on the same inputs without
 * saying anything. So this one keeps every failure silent and returns `null`, and callers show no badge.
 *
 * `null` for anything the JDK cannot read as an image: an empty byte array, a file that is not an
 * image, a truncated download. The reader is always disposed and the stream always closed, as in
 * [readRemoteImageSize] -- the editor walks a whole pager of pictures, and the JDK's `ImageReader`
 * holds the file handle open until it is disposed.
 */
fun readLocalImageSize(bytes: ByteArray): Pair<Int, Int>? =
    sniffImageSize(ImageIO.createImageInputStream(ByteArrayInputStream(bytes)))

/**
 * [readLocalImageSize] for a file on disk, e.g. `songs.uri`'s cover or a poster picked from a folder.
 *
 * Takes the path rather than a [String] so the "this is a path, never a URL" rule is in the type: a
 * `http://` string that reached here would be treated as a file name and quietly return `null`.
 */
fun readLocalImageSize(file: File): Pair<Int, Int>? =
    sniffImageSize(ImageIO.createImageInputStream(file))

private fun sniffImageSize(stream: ImageInputStream?): Pair<Int, Int>? {
    if (stream == null) return null
    var reader: ImageReader? = null
    return try {
        val readers = ImageIO.getImageReaders(stream)
        if (!readers.hasNext()) return null
        reader = readers.next()
        reader.input = stream
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        if (width > 0 && height > 0) width to height else null
    } catch (_: Exception) {
        // A file that is not an image, a header the JDK has no reader for, a permission failure: all of
        // them mean "no size badge", and none of them is worth interrupting the editor's scroll for.
        null
    } finally {
        reader?.dispose()
        runCatching { stream.close() }
    }
}
