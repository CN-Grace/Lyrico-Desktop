package com.lonx.lyrico.data.song.tag

import com.lonx.audiotag.model.AudioTagData
import com.lonx.audiotag.rw.AudioTagReader
import com.lonx.audiotag.rw.AudioTagWriter
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.utils.logging.PlatformLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Reads and writes song tags through `lyrico-audiotag` (TagLib + the app's own tag mapping).
 *
 * Desktop port notes — what changed against the Android original and why:
 * - No `Context`. The Android class needed it for `contentResolver` and `cacheDir`.
 * - No `ParcelFileDescriptor`: the native JNI layer takes a file path on Windows, so the tag
 *   library opens the song itself and the "writable descriptor, else ask for permission" dance is
 *   gone. A file that cannot be written simply throws.
 * - `readFromStreamCache` is deleted outright. It existed for SAF documents that could not be
 *   opened as a descriptor: they were copied into the cache directory and read from there. On
 *   Windows a song is always a real file, so the fallback has no case to serve.
 * - `android.util.Log` → [PlatformLog]; the structured log still goes to [AppLogRepository].
 *
 * The `uri` parameters keep their Android name because the `songs.uri` column does — it is the
 * database key. The value carried in it is a Windows absolute path (see PLAN.md §7).
 */
class AudioTagRepositoryImpl(
    private val fileAccess: AudioFileAccess,
    private val mutationResolver: AudioTagMutationResolver,
    private val appLogRepository: AppLogRepository
) : AudioTagRepository {

    override suspend fun read(
        uri: String,
        options: AudioTagReadOptions
    ): AudioTagData = if (options.strict) {
        readStrict(uri, options)
    } else {
        readLenient(uri, options)
    }

    private suspend fun readLenient(
        uri: String,
        options: AudioTagReadOptions
    ): AudioTagData = withContext(Dispatchers.IO) {
        val displayName = uri.toPathOrNull()?.let { fileAccess.getDisplayName(it) }.orEmpty()
        try {
            readFromPath(uri, displayName, strict = false, options = options)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            PlatformLog.e(TAG, "Failed to read audio tags: $uri", e)
            logMetadataException("Failed to read audio tags", e, uri)
            AudioTagData(fileName = displayName)
        }
    }

    private suspend fun readStrict(
        uri: String,
        options: AudioTagReadOptions = AudioTagReadOptions()
    ): AudioTagData = withContext(Dispatchers.IO) {
        val path = uri.toPathOrNull()
            ?: throw IllegalStateException("Not a filesystem path: $uri")
        readFromPath(path.toString(), fileAccess.getDisplayName(path), strict = true, options = options)
    }

    override suspend fun overwrite(
        uri: String,
        mutation: AudioTagMutation
    ): AudioTagWriteResult {
        return write(uri, mutation.copy(mode = AudioTagMutationMode.Overwrite))
    }

    override suspend fun patch(
        uri: String,
        mutation: AudioTagMutation
    ): AudioTagWriteResult {
        return write(uri, mutation.copy(mode = AudioTagMutationMode.Patch))
    }

    private suspend fun write(uri: String, mutation: AudioTagMutation): AudioTagWriteResult {
        return try {
            val path = uri.toPathOrNull()
                ?: return AudioTagWriteResult.Failed(IllegalStateException("Not a filesystem path: $uri"))
            val current = readStrict(uri)
            val resolved = mutationResolver.resolve(uri, current, mutation)

            if (resolved.tags.isNotEmpty()) {
                val tagsWritten = AudioTagWriter.writeTags(
                    path = path,
                    updates = resolved.tags,
                    preserveOldTags = true
                )
                if (!tagsWritten) {
                    return AudioTagWriteResult.Failed(IllegalStateException("Audio tag write failed"))
                }
            }

            when (val pictureCommand = resolved.pictures) {
                PictureWriteCommand.Unchanged -> Unit
                is PictureWriteCommand.ReplaceAll -> {
                    val picturesWritten = AudioTagWriter.writePictures(
                        path = path,
                        pictures = pictureCommand.pictures
                    )
                    if (!picturesWritten) {
                        return AudioTagWriteResult.Failed(
                            IllegalStateException("Audio picture write failed")
                        )
                    }
                }
            }

            AudioTagWriteResult.Success(readStrict(uri))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            PlatformLog.e(TAG, "Failed to write audio tags: $uri", e)
            logMetadataException("Failed to write audio tags", e, uri)
            AudioTagWriteResult.Failed(e)
        }
    }

    private suspend fun readFromPath(
        uri: String,
        displayName: String,
        strict: Boolean,
        options: AudioTagReadOptions
    ): AudioTagData {
        val path = uri.toPathOrNull()
        if (path == null) {
            if (strict) throw IllegalStateException("Not a filesystem path: $uri")
            return AudioTagData(fileName = displayName)
        }
        return AudioTagReader.read(
            path = path,
            readPictures = true,
            multiValueSeparator = options.multiValueSeparator,
            strict = strict
        ).copy(fileName = displayName)
    }

    private suspend fun logMetadataException(
        message: String,
        throwable: Throwable,
        relatedId: String? = null
    ) {
        try {
            appLogRepository.logException(
                type = AppLogType.METADATA,
                tag = TAG,
                message = message,
                throwable = throwable,
                relatedId = relatedId
            )
        } catch (e: Exception) {
            PlatformLog.w(TAG, "Failed to write metadata exception log", e)
        }
    }

    private companion object {
        const val TAG = "AudioTagRepository"

        /**
         * Null when the stored value is not a usable filesystem path — a `content://` row from an
         * Android library that was copied over without rescanning, or other malformed input. Callers
         * degrade instead of throwing, so one bad row cannot break a whole library read.
         */
        fun String?.toPathOrNull(): Path? = when {
            this.isNullOrBlank() -> null
            else -> try {
                Path.of(this)
            } catch (_: InvalidPathException) {
                null
            }
        }
    }
}
