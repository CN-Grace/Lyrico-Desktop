package com.lonx.lyrico.data.song.file

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.SongPaths
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.data.song.search.LyricFtsIndexer
import com.lonx.lyrico.data.utils.inTransaction
import com.lonx.lyrico.utils.logging.PlatformLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Deletes and renames song files on the local filesystem, keeping `lyrico.db` in step.
 *
 * The tricky part is not the file move, it is the tables that are keyed by the song's *path*:
 * `songs.uri` (and its mirrors `filePath`/`fileName`), the lyric FTS rows, and
 * `song_custom_tag_keys`. All of them change together or the library ends up inconsistent — a
 * renamed song that can no longer be found by its own lyrics, or a custom tag key that points at a
 * row nobody has. Hence one transaction per operation, and the reverse file operation if the
 * transaction fails: the disk and the database must not disagree at the end of a call.
 */
class SongFileRepositoryImpl(
    private val database: LyricoDatabase,
    private val fileAccess: AudioFileAccess,
    private val libraryIndexRepository: LibraryIndexRepository,
    private val sortKeyUpdater: SortKeyUpdater,
    private val appLogRepository: AppLogRepository
) : SongFileRepository {

    private val songDao = database.songDao()
    private val folderDao = database.folderDao()
    private val songCustomTagKeyDao = database.songCustomTagKeyDao()

    override suspend fun deleteSong(song: SongEntity): DeleteSongFileResult =
        deleteSongs(listOf(song)).items.single().result

    override suspend fun deleteSongs(songs: List<SongEntity>): BatchSongFileOperationResult =
        withContext(Dispatchers.IO) {
            if (songs.isEmpty()) return@withContext BatchSongFileOperationResult(emptyList())

            val items = songs.map { song ->
                BatchSongFileOperationItem(song = song, result = deleteFile(song))
            }

            // A song whose file is already gone still has its rows removed: the row describes a file
            // that is not there, which is exactly the state a scan would clean up anyway.
            val goneSongs = items
                .filter {
                    it.result is DeleteSongFileResult.Deleted || it.result is DeleteSongFileResult.AlreadyMissing
                }
                .map { it.song }

            if (goneSongs.isNotEmpty()) {
                val uris = goneSongs.map { it.uri }
                val folderIds = goneSongs.map { it.folderId }.distinct()
                database.inTransaction {
                    uris.chunked(BATCH_SIZE).forEach { chunk ->
                        songDao.deleteByUris(chunk)
                        // The FTS table and the custom tag keys are keyed by the uri, so deleting the
                        // song row is not enough to remove them.
                        songDao.deleteLyricFtsByUris(chunk)
                        songCustomTagKeyDao.deleteForSongs(chunk)
                    }
                    folderIds.forEach { folderId -> folderDao.refreshSongCount(folderId) }
                    folderDao.performPostScanCleanup()
                }
                libraryIndexRepository.refreshAndPruneIndexes()
            }

            logFailures(items, action = "delete")
            BatchSongFileOperationResult(items)
        }

    override suspend fun renameSong(
        song: SongEntity,
        newFileName: String
    ): RenameSongFileResult = withContext(Dispatchers.IO) {
        val oldPath = songPathOrNull(song)
            ?: return@withContext failed(
                IllegalStateException("Cannot rename a song without a filesystem path: ${song.uri}"),
                song,
            )
        if (!fileAccess.exists(oldPath)) {
            return@withContext failed(FileNotFoundException(song.uri), song)
        }

        val targetName = resolveTargetName(newFileName, oldPath)
            ?: return@withContext failed(
                IllegalArgumentException("Refused file name: $newFileName"),
                song,
            )

        val target = oldPath.resolveSibling(targetName)
        // On Windows the requested name may be the same file spelled differently ("Track.mp3" for
        // "track.mp3"). That is a legal rename — of the case — not a name conflict.
        val caseOnlyRename = SongPaths.identityKey(oldPath) == SongPaths.identityKey(target)
        if (!caseOnlyRename && fileAccess.exists(target)) {
            return@withContext RenameSongFileResult.NameConflict(targetName)
        }

        val newPath = try {
            if (caseOnlyRename) renameThroughTemporary(oldPath, target) else fileAccess.move(oldPath, targetName)
        } catch (e: Exception) {
            return@withContext failed(e, song)
        }

        // Re-read the path from disk rather than trusting the name we asked for: the filesystem may
        // hold a different spelling (NTFS keeps the case of the original for a case-only rename), and
        // `songs.uri` has to be the path that actually exists.
        val canonicalPath = SongPaths.canonicalize(newPath)
        val updatedSong = sortKeyUpdater.update(
            song.copy(
                uri = canonicalPath.toString(),
                filePath = canonicalPath.toString(),
                fileName = canonicalPath.fileName.toString(),
                fileExtension = canonicalPath.fileName.toString()
                    .substringAfterLast('.', "")
                    .uppercase(Locale.ROOT)
                    .takeIf { it.isNotBlank() },
                fileLastModified = Files.getLastModifiedTime(canonicalPath).toMillis(),
            )
        )

        try {
            database.inTransaction {
                songDao.update(updatedSong)
                // Both tables are keyed by the uri: the old rows have to go before the new ones are
                // written, or the song is indexed twice — once under a path nothing points at.
                songDao.deleteLyricFtsByUris(listOf(song.uri))
                LyricFtsIndexer.replaceSong(songDao, updatedSong)
                val customTagKeys = songCustomTagKeyDao.getKeysForSong(song.uri)
                if (customTagKeys.isNotEmpty()) {
                    songCustomTagKeyDao.deleteForSong(song.uri)
                    songCustomTagKeyDao.replaceForSong(updatedSong.uri, customTagKeys)
                }
                libraryIndexRepository.reindexSongInTransaction(updatedSong)
                folderDao.refreshSongCount(updatedSong.folderId)
            }
        } catch (e: Exception) {
            // The file is already renamed. Leaving it renamed while the database still says otherwise
            // would leave the library pointing at a path that does not exist, so the move is undone —
            // better a rename that failed than a song that vanished from the library.
            runCatching { fileAccess.move(canonicalPath, oldPath.fileName.toString()) }
                .onFailure { PlatformLog.e(TAG, "Failed to undo rename of $canonicalPath", it) }
            return@withContext failed(e, song)
        }

        libraryIndexRepository.refreshAndPruneIndexes()
        RenameSongFileResult.Success(song = updatedSong, oldUri = song.uri)
    }

    private fun deleteFile(song: SongEntity): DeleteSongFileResult {
        val path = songPathOrNull(song)
            ?: return DeleteSongFileResult.Failed(IllegalStateException("Song has no filesystem path: ${song.uri}"))
        if (!fileAccess.exists(path)) return DeleteSongFileResult.AlreadyMissing

        return try {
            fileAccess.delete(path)
            DeleteSongFileResult.Deleted
        } catch (e: Exception) {
            DeleteSongFileResult.Failed(e)
        }
    }

    /**
     * A rename happens inside the song's own folder, so the new name is validated to be a bare file
     * name: a separator, a drive letter or `..` would move the song out of the folder the library
     * knows about.
     *
     * A name with no extension at all keeps the current one. Renaming `Track.mp3` to `Track 2019`
     * would otherwise leave a file the scanner (which classifies by extension) would never find
     * again. The rename dialog always sends the extension back, so this only guards other callers.
     */
    private fun resolveTargetName(newFileName: String, oldPath: Path): String? {
        val trimmed = newFileName.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "." || trimmed == "..") return null
        if (trimmed.any { it in INVALID_NAME_CHARS }) return null
        // `Path.of` collapses the parts; if it is not a single element the name was a path.
        val parsed = try {
            Path.of(trimmed)
        } catch (_: Exception) {
            return null
        }
        if (parsed.nameCount != 1 || parsed.fileName.toString() != trimmed) return null

        val currentExtension = oldPath.fileName.toString().substringAfterLast('.', "")
        if (currentExtension.isEmpty() || trimmed.contains('.')) return trimmed
        return "$trimmed.$currentExtension"
    }

    /** `a.mp3` → `b.mp3` on a case-insensitive filesystem has to go through a third name. */
    private fun renameThroughTemporary(source: Path, target: Path): Path {
        val temporaryName = "${target.fileName}.lyrico-rename-tmp"
        val temporary = fileAccess.move(source, temporaryName)
        return try {
            fileAccess.move(temporary, target.fileName.toString())
        } catch (e: Exception) {
            runCatching { fileAccess.move(temporary, source.fileName.toString()) }
            throw e
        }
    }

    private fun songPathOrNull(song: SongEntity): Path? =
        SongPaths.canonicalize(song.uri) ?: SongPaths.canonicalize(song.filePath)

    private suspend fun failed(throwable: Throwable, song: SongEntity): RenameSongFileResult {
        PlatformLog.e(TAG, "Failed to rename ${song.uri}", throwable)
        logException("Failed to rename song: ${song.fileName}", throwable, song.uri)
        return RenameSongFileResult.Failed(throwable)
    }

    private suspend fun logFailures(items: List<BatchSongFileOperationItem>, action: String) {
        items.forEach { item ->
            val throwable = (item.result as? DeleteSongFileResult.Failed)?.throwable ?: return@forEach
            PlatformLog.e(TAG, "Failed to $action ${item.song.uri}", throwable)
            logException("Failed to $action song: ${item.song.fileName}", throwable, item.song.uri)
        }
    }

    private suspend fun logException(message: String, throwable: Throwable, relatedId: String?) {
        try {
            appLogRepository.logException(
                type = AppLogType.APP,
                tag = TAG,
                message = message,
                throwable = throwable,
                relatedId = relatedId
            )
        } catch (e: Exception) {
            PlatformLog.w(TAG, "Failed to write exception log", e)
        }
    }

    private companion object {
        const val TAG = "SongFileRepository"
        const val BATCH_SIZE = 50

        /** Characters Windows refuses in a file name, plus the two path separators of any platform. */
        val INVALID_NAME_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    }
}
