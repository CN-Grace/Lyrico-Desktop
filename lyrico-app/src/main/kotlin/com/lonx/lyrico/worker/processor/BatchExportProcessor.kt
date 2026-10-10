package com.lonx.lyrico.worker.processor

import com.lonx.audiotag.model.frontCoverOrFallback
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.ExportDestination
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The export task's configuration.
 *
 * `destinationTreeUri` became `destinationDirectory`: the "write into a folder the user picked" mode
 * stored a SAF tree URI on Android, and there is no SAF here — the directory picker hands back a
 * plain Windows path. Decoding is lenient (`ignoreUnknownKeys`) for the one case where that rename
 * matters. A row written by Android with `AUDIO_DIRECTORY` also carries `destinationTreeUri`, which
 * that mode never read, so such a task keeps working on the desktop. A `SELECTED_DIRECTORY` row does
 * not: a `content://` tree URI is not a path, and the item fails with "Destination folder
 * unavailable" instead of writing into some directory that happens to be named like the URI.
 */
@Serializable
data class BatchExportTaskConfig(
    val destinationDirectory: String? = null,
    val destination: ExportDestination = ExportDestination.SELECTED_DIRECTORY,
    val concurrency: Int = 3
)

/**
 * Batch processor that writes a song's lyrics or cover art out as a file next to nothing the library
 * tracks: a plain `.lrc`/`.ttml`/`.jpg` the user can hand to another player.
 *
 * Ported from Android essentially unchanged: same skip rules (no lyrics or no cover skips the item
 * rather than failing it), same file names (`<song base name>.<extension>`), same TTML detection,
 * same overwrite-in-place behaviour, same two destinations (`SELECTED_DIRECTORY` and
 * `AUDIO_DIRECTORY`), same result shape (the runner stores `updatedFilePath`/`updatedFileName` on the
 * item row, which is what the batch detail screen shows).
 *
 * Three desktop differences:
 *
 *  * **The cover is bytes, not an `Any?`.** Android switched on six `CoverSourceType` cases because
 *    the edit-metadata screen could hand this processor a `Uri` or a network URL. Here the only
 *    source is the tag reader, which always returns `Picture.data` — a `ByteArray`. The import path
 *    that could produce the other shapes stays on Android's side of the port.
 *  * **`Files.write`, not SAF.** `DocumentFile.findFile`-then-`openOutputStream("wt")` and the
 *    sibling-file walk in `SafSiblingFileWriter` both collapse to one `java.nio` call: the file name
 *    is resolved against the destination directory (or the audio file's own directory) and written
 *    with `CREATE`/`TRUNCATE_EXISTING`, so an existing export is replaced rather than duplicated as
 *    `name (1).lrc`. Android had two failure messages for the two steps; a single call has one, so
 *    the create message is the one kept — with the target path appended, because "Failed to create
 *    lyrics file" without saying where is not something a user can act on.
 *  * **Messages name the probed path.** Android's "Destination folder unavailable" / "is not
 *    writable" are kept verbatim, and the path that was checked is appended. The English text is
 *    still the raw processor message — user-facing translation is a separate, unported piece of work.
 */
class BatchExportProcessor(
    private val audioTagRepository: AudioTagRepository
) : BatchTaskProcessor {

    /**
     * One writer at a time for the next-to-audio destination. Android needed this because two items
     * can resolve to the same target name (a folder holding `song.mp3` and `song.flac` both export to
     * `song.lrc`) and the SAF provider created the document lazily; serializing keeps the same
     * last-writer-wins order here, and keeps a target from being written by two items at once.
     */
    private val audioDirectoryWriteMutex = Mutex()

    override suspend fun process(
        task: BatchTaskEntity,
        item: BatchTaskItemEntity,
        onProgress: suspend (Float) -> Unit
    ): BatchTaskProcessResult {
        val config = task.configJson?.let {
            CONFIG_JSON.decodeFromString<BatchExportTaskConfig>(it)
        } ?: throw BatchTaskSkippedException("No config")

        val tagData = audioTagRepository.read(item.songUri, AudioTagReadOptions(strict = true))
        val result = when (task.type) {
            BatchTaskType.EXPORT_LYRICS -> when (config.destination) {
                ExportDestination.SELECTED_DIRECTORY -> exportLyrics(
                    item = item,
                    lyrics = tagData.lyrics,
                    directory = requireSelectedDirectory(config)
                )

                ExportDestination.AUDIO_DIRECTORY -> audioDirectoryWriteMutex.withLock {
                    exportLyricsNextToAudio(item, tagData.lyrics)
                }
            }

            BatchTaskType.EXPORT_COVER -> {
                val coverBytes = tagData.pictures.frontCoverOrFallback()?.data
                when (config.destination) {
                    ExportDestination.SELECTED_DIRECTORY -> exportCover(
                        item = item,
                        coverBytes = coverBytes,
                        directory = requireSelectedDirectory(config)
                    )

                    ExportDestination.AUDIO_DIRECTORY -> audioDirectoryWriteMutex.withLock {
                        exportCoverNextToAudio(item, coverBytes)
                    }
                }
            }

            else -> throw IllegalArgumentException("Unsupported export task type: ${task.type}")
        }
        onProgress(1f)
        return result
    }

    /**
     * The desktop half of `DocumentFile.fromTreeUri(...)` + `canWrite()`: the configured directory has
     * to exist and be writable. It is not created on demand -- the Android folder picker created it,
     * and a typo in a stored path should fail loudly rather than scatter exports into a fresh folder.
     */
    private fun requireSelectedDirectory(config: BatchExportTaskConfig): Path {
        val raw = config.destinationDirectory?.takeIf { it.isNotBlank() }
            ?: throw Exception("Destination folder unavailable")
        val directory = runCatching { Path.of(raw).toAbsolutePath() }.getOrNull()
            ?: throw Exception("Destination folder unavailable: $raw")
        if (!Files.isDirectory(directory)) {
            throw Exception("Destination folder unavailable: $directory")
        }
        if (!Files.isWritable(directory)) {
            throw Exception("Destination folder is not writable: $directory")
        }
        return directory
    }

    private fun exportLyrics(
        item: BatchTaskItemEntity,
        lyrics: String?,
        directory: Path
    ): BatchTaskProcessResult {
        if (lyrics.isNullOrBlank()) throw BatchTaskSkippedException("No lyrics")

        val fileName = "${item.baseFileName()}.${lyricsExtension(lyrics)}"
        val file = directory.resolve(fileName)
        writeBytes(file, lyrics.toByteArray(Charsets.UTF_8), "Failed to create lyrics file")

        return BatchTaskProcessResult(
            updatedFilePath = file.toString(),
            updatedFileName = fileName
        )
    }

    private fun exportLyricsNextToAudio(
        item: BatchTaskItemEntity,
        lyrics: String?
    ): BatchTaskProcessResult {
        if (lyrics.isNullOrBlank()) throw BatchTaskSkippedException("No lyrics")

        val fileName = "${item.baseFileName()}.${lyricsExtension(lyrics)}"
        val file = siblingOfAudio(item, fileName)
        writeBytes(file, lyrics.toByteArray(Charsets.UTF_8), "Failed to create lyrics file")

        return BatchTaskProcessResult(
            updatedFilePath = file.toString(),
            updatedFileName = fileName
        )
    }

    private fun exportCover(
        item: BatchTaskItemEntity,
        coverBytes: ByteArray?,
        directory: Path
    ): BatchTaskProcessResult {
        if (coverBytes == null) throw BatchTaskSkippedException("No cover")

        val fileName = "${item.baseFileName()}.jpg"
        val file = directory.resolve(fileName)
        writeBytes(file, coverBytes, "Failed to create cover file")

        return BatchTaskProcessResult(
            updatedFilePath = file.toString(),
            updatedFileName = fileName
        )
    }

    private fun exportCoverNextToAudio(
        item: BatchTaskItemEntity,
        coverBytes: ByteArray?
    ): BatchTaskProcessResult {
        if (coverBytes == null) throw BatchTaskSkippedException("No cover")

        val fileName = "${item.baseFileName()}.jpg"
        val file = siblingOfAudio(item, fileName)
        writeBytes(file, coverBytes, "Failed to create cover file")

        return BatchTaskProcessResult(
            updatedFilePath = file.toString(),
            updatedFileName = fileName
        )
    }

    /**
     * `SafSiblingFileWriter`'s parent-document walk, minus SAF: the item's `songUri` is the song's
     * absolute path on the desktop, so its directory is the destination.
     */
    private fun siblingOfAudio(item: BatchTaskItemEntity, fileName: String): Path {
        val songPath = Path.of(item.songUri).toAbsolutePath()
        val directory = songPath.parent
            ?: throw Exception("Destination folder unavailable: $songPath")
        return directory.resolve(fileName)
    }

    /** A single `java.nio` write is what `openOutputStream(uri, "wt")` meant on Android. */
    private fun writeBytes(target: Path, bytes: ByteArray, failureMessage: String) {
        try {
            Files.write(
                target,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        } catch (e: IOException) {
            throw Exception("$failureMessage: $target (${e.message ?: e::class.java.simpleName})", e)
        }
    }

    /** A song file without a dot keeps its whole name, as `missingDelimiterValue` did on Android. */
    private fun BatchTaskItemEntity.baseFileName(): String =
        fileName.substringBeforeLast(".", missingDelimiterValue = fileName)

    /** Android's `detectLyricsFormat`: TTML is the lyrics flavor that arrives as an XML document. */
    private fun lyricsExtension(lyrics: String): String =
        if (lyrics.contains("begin=") && lyrics.contains("end=") && lyrics.contains("<?xml")) {
            "ttml"
        } else {
            "lrc"
        }

    private companion object {
        /**
         * Lenient on purpose, unlike the other processors' strict decode: see
         * [BatchExportTaskConfig]. `encodeDefaults` is left alone -- the launch screen writes the
         * destination it was given, and a missing key decodes to the same default the class declares.
         */
        val CONFIG_JSON = Json { ignoreUnknownKeys = true }
    }
}
