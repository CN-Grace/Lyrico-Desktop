package com.lonx.lyrico.data.song.scan

import com.lonx.lyrico.data.model.SongFile
import com.lonx.lyrico.data.model.SongPaths
import com.lonx.lyrico.data.model.entity.FolderEntity
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs

/** One audio file found on disk, with the library root it was found under. */
data class ScannedSongFile(
    val songFile: SongFile,
    val rootFolderId: Long,
    /** The directory holding the file, canonical, as stored in `folders.path`. */
    val folderPath: String
)

/** What one [MediaScanner.scan] pass saw, per library root. */
data class ScanFolderResult(
    val songs: List<ScannedSongFile>,
    val successfulFolderIds: Set<Long>,
    val failedFolderIds: Set<Long>,
    val missingFolderIds: Set<Long>
)

/**
 * Walks library roots on the local filesystem and returns the audio files it finds.
 *
 * This replaces Android's SAF scanner (`querySongsFromSafFolders`, `DocumentsContract` cursors).
 * The traversal is the same pre-order directory walk with the same audio extension set, but the
 * rules that only made sense for a phone have been re-decided for a Windows library — see
 * [shouldSkipDirectory] — and two Windows-specific hazards are handled that SAF could not have:
 *
 * 1. **Junction cycles.** A junction (or symlink) inside a library can point at one of its own
 *    ancestors. SAF document ids are acyclic, so Android's walk could not loop; on NTFS it can, and
 *    the walk would never end. Every directory is therefore visited at most once, keyed by its real
 *    path.
 * 2. **One file, one row.** A library added twice, or reached through two junctions, must not
 *    produce the database's UNIQUE `songs.uri` twice. Songs are de-duplicated by canonical path
 *    across the whole pass, and directories by real path, so a file reachable two ways is scanned
 *    once.
 *
 * Scanning never follows a directory link to *report* it as a different folder: because paths are
 * canonicalised, a file found through a junction is stored under its real location. That is the
 * documented "one target is one library" policy in [SongPaths].
 */
class MediaScanner(
    private val fileSystem: SongFileSystem = NioSongFileSystem()
) {

    /**
     * Scans [folders] (library roots) and returns everything found.
     *
     * A root whose directory does not exist is reported in [ScanFolderResult.missingFolderIds]; a
     * root that cannot be read is reported in [ScanFolderResult.failedFolderIds]. Neither aborts the
     * pass: the caller decides what to do about them, and the other roots still get scanned.
     */
    fun scan(folders: List<FolderEntity>): ScanFolderResult {
        val songs = mutableListOf<ScannedSongFile>()
        val successfulFolderIds = mutableSetOf<Long>()
        val failedFolderIds = mutableSetOf<Long>()
        val missingFolderIds = mutableSetOf<Long>()
        val visitedRealDirectories = mutableSetOf<Path>()
        val visitedSongPaths = mutableSetOf<String>()

        for (folder in folders) {
            val root = parseRoot(folder.path)
            if (root == null) {
                missingFolderIds.add(folder.id)
                continue
            }
            if (!fileSystem.isDirectory(root)) {
                missingFolderIds.add(folder.id)
                continue
            }

            try {
                walk(
                    root = root,
                    rootFolderId = folder.id,
                    songs = songs,
                    visitedRealDirectories = visitedRealDirectories,
                    visitedSongPaths = visitedSongPaths
                )
                successfulFolderIds.add(folder.id)
            } catch (_: Exception) {
                // One unreadable root must not lose the songs found under the others.
                failedFolderIds.add(folder.id)
            }
        }

        return ScanFolderResult(
            songs = songs,
            successfulFolderIds = successfulFolderIds,
            failedFolderIds = failedFolderIds,
            missingFolderIds = missingFolderIds
        )
    }

    private fun walk(
        root: Path,
        rootFolderId: Long,
        songs: MutableList<ScannedSongFile>,
        visitedRealDirectories: MutableSet<Path>,
        visitedSongPaths: MutableSet<String>
    ) {
        val pending = ArrayDeque<Path>()
        pending.addLast(root)
        visitedRealDirectories.add(fileSystem.realPath(root))

        while (pending.isNotEmpty()) {
            val directory = pending.removeLast()

            val entries = try {
                fileSystem.list(directory)
            } catch (e: Exception) {
                // An unreadable subdirectory is skipped: permissions on one folder (or a race with
                // a folder being deleted) must not fail the whole library. The root is different —
                // if that cannot be read there is nothing to report but the failure itself, so the
                // caller hears about it (it becomes a failedFolderId) instead of seeing a scan that
                // quietly found nothing.
                if (directory == root) throw e
                continue
            }

            for (entry in entries) {
                val name = entry.fileName?.toString() ?: continue
                if (name.isBlank()) continue

                val attributes = try {
                    fileSystem.attributes(entry)
                } catch (_: Exception) {
                    continue
                }

                if (attributes.isDirectory) {
                    if (shouldSkipDirectory(name)) continue
                    // A junction can point back at an ancestor; visiting by real path terminates
                    // the walk instead of following the loop forever.
                    if (!visitedRealDirectories.add(fileSystem.realPath(entry))) continue
                    pending.addLast(entry)
                    continue
                }

                if (shouldSkipFile(name)) continue
                if (!isSupportedAudioFile(name)) continue

                val path = SongPaths.canonicalize(entry)
                if (!visitedSongPaths.add(path.toString())) continue

                songs.add(
                    ScannedSongFile(
                        rootFolderId = rootFolderId,
                        folderPath = path.parent?.toString() ?: directory.toString(),
                        songFile = SongFile(
                            mediaId = createVirtualMediaId(path.toString()),
                            path = path,
                            filePath = path.toString(),
                            fileName = path.fileName.toString(),
                            lastModified = attributes.lastModified,
                            // Windows keeps a real creation time, which is what "date added" means
                            // for a library that was copied onto this disk; the modification time
                            // is the fallback for filesystems that do not record one.
                            dateAdded = attributes.created.takeIf { it > 0L } ?: attributes.lastModified,
                            duration = 0L,
                            fileSize = attributes.size
                        )
                    )
                )
            }
        }
    }

    private fun parseRoot(path: String): Path? = SongPaths.canonicalize(path)

    /**
     * Directories that are never part of a music library.
     *
     * Android's list also skipped `data`, `cache`, `tmp` and `obb` because on a phone those are
     * system trees full of non-music files. On Windows they are ordinary folder names a user may
     * well have used, and silently skipping them would hide songs, so only genuinely system-owned
     * directories are skipped here: hidden ones (`.git`, `.thumbnails`), the two directories Windows
     * itself creates at the root of a volume, and the Android system trees kept for a library
     * copied over from a device.
     */
    private fun shouldSkipDirectory(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return normalized.startsWith(".") ||
            normalized in SKIPPED_DIRECTORY_NAMES
    }

    /**
     * Hidden files are skipped as well as hidden directories: a library copied from macOS contains
     * `._Track.mp3` resource forks, which would otherwise match `.mp3` and be imported as unplayable
     * "songs".
     */
    private fun shouldSkipFile(name: String): Boolean = name.startsWith(".")

    /**
     * The same extension set Android used (minus `mp4`, dropped upstream as a video container), with
     * no MIME fallback: on a filesystem the extension is the only signal, so a file with an unknown
     * extension is left alone rather than guessed at by content.
     */
    private fun isSupportedAudioFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return extension in SUPPORTED_AUDIO_EXTENSIONS
    }

    /**
     * `songs.mediaId` is not used on Windows (nothing has a media store row id), but the column
     * exists in the Android schema and is NOT NULL, so it keeps Android's SAF behaviour: a stable
     * negative number derived from the path, never 0.
     */
    private fun createVirtualMediaId(path: String): Long {
        val hash = path.hashCode().toLong()
        return -abs(hash).coerceAtLeast(1L)
    }

    private companion object {
        val SUPPORTED_AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma", "ape"
        )

        val SKIPPED_DIRECTORY_NAMES = setOf(
            "\$recycle.bin",
            "system volume information",
            "android",
            "obb"
        )
    }
}
