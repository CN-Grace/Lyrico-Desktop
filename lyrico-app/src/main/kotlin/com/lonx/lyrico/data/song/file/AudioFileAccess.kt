package com.lonx.lyrico.data.song.file

import java.nio.file.FileAlreadyExistsException
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
     * True when the file is gone. Callers use it to tell "the file was already deleted outside the
     * app" apart from a failed operation.
     */
    fun isUriMissing(path: Path): Boolean = !Files.exists(path)

    /** True when the file existed and was deleted; false when it is missing or deletion failed. */
    fun deleteDocument(path: Path): Boolean = try {
        Files.delete(path)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Renames the file inside its own directory, returning its new path, or null when the new name is
     * already taken or the move fails — the same contract as `DocumentsContract.renameDocument`.
     */
    fun renameDocument(path: Path, newFileName: String): Path? {
        val parent = path.parent ?: return null
        return try {
            Files.move(path, parent.resolve(newFileName))
        } catch (_: FileAlreadyExistsException) {
            null
        } catch (_: Exception) {
            null
        }
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
