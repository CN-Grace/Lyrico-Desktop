package com.lonx.lyrico.utils.coil

import com.lonx.audiotag.rw.AudioTagReader
import com.lonx.lyrico.utils.logging.PlatformLog
import java.io.File
import kotlinx.coroutines.CancellationException

private const val TAG = "ArtistPoster"

internal inline fun <T> readArtworkSafely(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    PlatformLog.w(TAG, "Artwork read failed", e)
    null
}

/**
 * Poster files are user supplied, so an unreadable file must be skipped instead of shadowing a
 * working one in the same folder.
 *
 * Android asked `BitmapFactory` whether it could measure the bytes; desktop asks the same decoder
 * Coil renders with (Skia), so a file accepted here is a file that will actually display.
 */
private fun ByteArray.validArtwork(): ByteArray? {
    if (isEmpty()) return null
    val image = try {
        org.jetbrains.skia.Image.makeFromEncoded(this)
    } catch (e: Exception) {
        return null
    } ?: return null
    return try {
        if (image.width <= 0 || image.height <= 0) null else this
    } finally {
        image.close()
    }
}

/**
 * Looks up an artist poster in the user's poster folders, in the order the folders were added.
 * Files named exactly after the artist win over suffixed ones inside each folder.
 *
 * Android listed each folder through the Storage Access Framework; desktop folders are plain
 * directories, and the ranking is the same [ArtistPosterMatcher] the poster-folder screen uses.
 */
internal suspend fun readExternalArtistPoster(artist: String?, folders: List<String>): ByteArray? {
    if (artist.isNullOrBlank() || folders.isEmpty()) return null
    for (folder in folders) {
        val files = File(folder).listFiles()
            ?.filterNot { it.isDirectory }
            ?.filter { ArtistPosterMatcher.isPosterFile(it.name) }
            ?: continue
        val matches = files
            .mapNotNull { file -> ArtistPosterMatcher.rank(file.name, artist)?.let { rank -> rank to file } }
            .sortedWith(compareBy({ it.first }, { it.second.name.lowercase() }, { it.second.name }))
        for ((_, file) in matches) {
            val bytes = readPosterBytes(file, file.name)
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
    }
    return null
}

/**
 * Reads one poster file.
 *
 * `.mp4` posters were video stills on Android (`MediaMetadataRetriever`). Windows has no video
 * decoder in-process, so the file's embedded cover is used when it has one; a video without one is
 * skipped and the next matching poster is tried.
 */
internal suspend fun readPosterBytes(file: File, name: String): ByteArray? = readArtworkSafely {
    val bytes = if (name.endsWith(".mp4", ignoreCase = true)) {
        AudioTagReader.readPicture(path = file.toPath())
    } else {
        file.readBytes()
    }
    bytes.validArtwork()
}
