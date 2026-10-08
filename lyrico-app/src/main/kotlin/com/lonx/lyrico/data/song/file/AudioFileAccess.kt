package com.lonx.lyrico.data.song.file

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File operations on a song file, ported from the Android SAF/MediaStore helper.
 *
 * The Android original resolved `content://` URIs to descriptors through `ContentResolver`,
 * `DocumentsContract` and the `MediaStore` audio collection, and returned `IntentSender`s when a
 * write needed the user to grant permission. Windows has none of that: a song is identified by its
 * absolute path, the process either may write the file or not, so the permission flow disappears
 * and every method here is a thin `java.nio.file` call.
 *
 * The descriptor-opening methods of the Android class are gone as well — the tag layer passes a
 * [Path] straight to `lyrico-audiotag`, which opens the file natively.
 */
class AudioFileAccess {

    /** File name including the extension, e.g. `Song.flac`. */
    fun getDisplayName(path: Path): String = path.fileName?.toString().orEmpty()

    /**
     * True when the file is there. Callers use it to tell "the file was already deleted outside the
     * app" apart from a failed operation.
     */
    fun exists(path: Path): Boolean = Files.exists(path)

    /**
     * Deletes the file. Unlike the Android original, which reported a bare false, this throws: the
     * caller has to log *why* a delete failed (a locked file, a read-only file, a file that turned
     * out to be a directory), and a boolean would throw that away. Missing files are reported by
     * [exists] before the call.
     */
    fun delete(path: Path) {
        Files.delete(path)
    }

    /**
     * Renames the file inside its own directory and returns its new path.
     *
     * Throws (rather than returning null the way `DocumentsContract.renameDocument` did) so the
     * caller can distinguish a name that is already taken from a move the filesystem refused.
     */
    fun move(path: Path, newFileName: String): Path {
        val parent = path.parent ?: throw IllegalArgumentException("cannot rename a root path: $path")
        return Files.move(path, parent.resolve(newFileName))
    }

    /** Reads the whole file, or null when it cannot be read. */
    fun openInputBytes(path: Path): ByteArray? = try {
        Files.readAllBytes(path)
    } catch (_: Exception) {
        null
    }

    /**
     * Writes [bytes] to [path], replacing it atomically where the platform allows. Used by the
     * export flows, which write a new file rather than mutate an existing one.
     */
    fun writeBytes(path: Path, bytes: ByteArray): Boolean = try {
        path.parent?.let { Files.createDirectories(it) }
        val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        Files.write(temporary, bytes)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (_: Exception) {
        false
    }
}
