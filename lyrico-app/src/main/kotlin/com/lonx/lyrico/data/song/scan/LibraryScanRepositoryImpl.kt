package com.lonx.lyrico.data.song.scan

import com.lonx.audiotag.model.CustomTagField
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.SongFile
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.mapper.SongMetadataMapper
import com.lonx.lyrico.data.song.search.LyricFtsIndexer
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.utils.inTransaction
import com.lonx.lyrico.utils.logging.PlatformLog
import com.lonx.lyrico.utils.LyricsSearchTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Synchronises the database with the audio files on disk: walk the library roots, read the tags of
 * every file that is new or changed, write the rows, delete the rows of files that are gone, and
 * refresh the artist/album indexes.
 *
 * Only the file source exists on Windows (Android's MediaStore and SAF branches are gone), so the
 * rows this repository owns are exactly the ones with `songs.source = "LOCAL"`. Rows inherited from
 * an Android database keep their `MEDIA_STORE`/`SAF` source and their `content://` uri: they are not
 * files on this disk, so a scan neither matches nor deletes them.
 */
class LibraryScanRepositoryImpl(
    private val database: LyricoDatabase,
    private val mediaScanner: MediaScanner,
    private val settingsRepository: SettingsRepository,
    private val audioTagRepository: AudioTagRepository,
    private val songMetadataMapper: SongMetadataMapper,
    private val libraryIndexRepository: LibraryIndexRepository,
    private val appLogRepository: AppLogRepository
) : LibraryScanRepository {

    private val songDao = database.songDao()
    private val folderDao = database.folderDao()
    private val songCustomTagKeyDao = database.songCustomTagKeyDao()

    /** One scan at a time: two concurrent passes would fight over the same rows and indexes. */
    private val syncMutex = Mutex()

    override suspend fun synchronize(
        request: LibraryScanRequest,
        onProgress: suspend (LibraryScanProgress) -> Unit
    ): LibraryScanResult {
        return syncMutex.withLock {
            withContext(Dispatchers.IO) {
                synchronizeLocked(request, onProgress)
            }
        }
    }

    private suspend fun synchronizeLocked(
        request: LibraryScanRequest,
        onProgress: suspend (LibraryScanProgress) -> Unit
    ): LibraryScanResult {
        try {
            PlatformLog.d(TAG, "Start library sync: fullRescan=${request.fullRescan}")
            onProgress(LibraryScanProgress(stage = LibraryScanStage.LISTING_FILES))

            val dbSyncInfos = songDao.getAllSyncInfo()
            val dbSongMap = dbSyncInfos.associateBy { it.uri }

            val deviceUris = mutableSetOf<String>()
            val impactedFolderIds = mutableSetOf<Long>()
            val failures = mutableListOf<LibraryScanFailure>()
            val songsToUpsert = mutableListOf<ScannedSongMetadata>()
            val indexLyrics = settingsRepository.lyricIndexEnabled.first()
            val separator = settingsRepository.separator.first()
            val unchangedSongUris = mutableListOf<String>()

            val rootFolders = if (request.folderIds == null) {
                folderDao.getLibraryRootFolders()
            } else {
                folderDao.getScanRootFoldersFor(request.folderIds)
            }
            val rootFolderById = rootFolders.associateBy { it.id }
            val scanResult = mediaScanner.scan(rootFolders)
            val deviceSongs = scanResult.songs

            // A root that is not there right now (an unplugged drive, a moved folder) is reported
            // instead of silently ignored, so the user learns why songs are missing.
            scanResult.missingFolderIds.forEach { folderId ->
                failures.add(
                    LibraryScanFailure(
                        path = rootFolderById[folderId]?.path,
                        fileName = null,
                        stage = LibraryScanFailureStage.Collecting,
                        message = "Library folder not found",
                    )
                )
            }
            scanResult.failedFolderIds.forEach { folderId ->
                failures.add(
                    LibraryScanFailure(
                        path = rootFolderById[folderId]?.path,
                        fileName = null,
                        stage = LibraryScanFailureStage.Collecting,
                        message = "Library folder could not be read",
                    )
                )
            }

            onProgress(
                LibraryScanProgress(
                    stage = LibraryScanStage.READING_METADATA,
                    current = 0,
                    total = deviceSongs.size
                )
            )

            for ((index, scannedSong) in deviceSongs.withIndex()) {
                val deviceSong = scannedSong.songFile
                try {
                    val deviceUriString = deviceSong.path.toString()
                    val dbInfo = dbSongMap[deviceUriString]
                    val needsUpdate = request.fullRescan ||
                        dbInfo == null ||
                        dbInfo.fileLastModified != deviceSong.lastModified ||
                        dbInfo.fileSize != deviceSong.fileSize ||
                        dbInfo.filePath != deviceSong.filePath
                    val knownDuration = when {
                        deviceSong.duration > 0L -> deviceSong.duration
                        !needsUpdate -> dbInfo.durationMilliseconds.toLong()
                        else -> 0L
                    }

                    if (
                        request.ignoreShortAudio &&
                        knownDuration > 0L &&
                        knownDuration <= MIN_DURATION_MILLIS
                    ) {
                        continue
                    }

                    if (needsUpdate) {
                        val rootFolder = rootFolderById[scannedSong.rootFolderId]
                        val folderId = folderDao.upsertScannedFolderTreeAndGetLeafId(
                            rootPath = rootFolder?.path ?: scannedSong.folderPath,
                            folderPath = scannedSong.folderPath,
                            isIgnored = rootFolder?.isIgnored ?: false
                        )
                        impactedFolderIds.add(folderId)

                        val metadata = extractSongMetadata(
                            songFile = deviceSong,
                            folderId = folderId,
                            existingId = dbInfo?.id ?: 0L,
                            indexLyrics = indexLyrics,
                            separator = separator
                        )

                        if (
                            request.ignoreShortAudio &&
                            metadata != null &&
                            metadata.entity.durationMilliseconds > 0 &&
                            metadata.entity.durationMilliseconds <= MIN_DURATION_MILLIS
                        ) {
                            continue
                        }

                        metadata?.let(songsToUpsert::add)
                    } else if (indexLyrics) {
                        unchangedSongUris.add(deviceUriString)
                    }

                    deviceUris.add(deviceUriString)
                } catch (e: Exception) {
                    PlatformLog.e(TAG, "Failed to process song: ${deviceSong.fileName}", e)
                    failures.add(
                        LibraryScanFailure(
                            path = deviceSong.path.toString(),
                            fileName = deviceSong.fileName,
                            stage = LibraryScanFailureStage.ReadingMetadata,
                            message = e.message ?: e::class.java.simpleName,
                            throwable = e
                        )
                    )
                } finally {
                    onProgress(
                        LibraryScanProgress(
                            stage = LibraryScanStage.READING_METADATA,
                            current = index + 1,
                            total = deviceSongs.size,
                            currentFile = deviceSong.fileName
                        )
                    )
                }
            }

            // Only folders that were actually read can prove a file is gone; a folder that was
            // missing or unreadable says nothing, so its songs are left alone.
            val successfulScannedFolderIds =
                folderDao.getFolderTreeIds(scanResult.successfulFolderIds).toSet()

            val deletedUris = dbSyncInfos
                .filter { info ->
                    info.source == SongSource.LOCAL &&
                        info.folderId in successfulScannedFolderIds &&
                        info.uri !in deviceUris
                }
                .map { it.uri }
                .toSet()

            // Deliberately opt-in: see LibraryScanRequest.removeUnavailableFolders. The roots are
            // expanded to their whole trees first: scanResult reports the *root* that vanished,
            // while songs are filed under the leaf folder that held them, and matching the two by id
            // alone would leave the songs behind.
            val unavailableFolderIds = if (request.removeUnavailableFolders) {
                scanResult.missingFolderIds
            } else {
                emptySet()
            }
            val unavailableFolderTreeIds = if (unavailableFolderIds.isEmpty()) {
                emptySet()
            } else {
                folderDao.getFolderTreeIds(unavailableFolderIds).toSet()
            }
            val unavailableFolderSongUris = dbSyncInfos
                .filter { info -> info.folderId in unavailableFolderTreeIds }
                .map { it.uri }
                .toSet()

            val allDeletedUris = deletedUris + unavailableFolderSongUris
            if (allDeletedUris.isNotEmpty()) {
                impactedFolderIds.addAll(
                    dbSyncInfos.filter { it.uri in allDeletedUris }.map { it.folderId }
                )
            }
            impactedFolderIds.addAll(unavailableFolderTreeIds)

            val lyricsToIndex = unchangedSongUris.chunked(BATCH_SIZE).flatMap { uris ->
                songDao.getSongLyricsMissingIndex(uris).map { row ->
                    row.copy(lyricSearchText = LyricsSearchTextExtractor.toSearchText(row.lyrics).orEmpty())
                }
            }
            val databaseChanges = songsToUpsert.size + allDeletedUris.size +
                unavailableFolderIds.size + lyricsToIndex.size
            onProgress(
                LibraryScanProgress(
                    stage = LibraryScanStage.WRITING_DATABASE,
                    current = 0,
                    total = databaseChanges
                )
            )

            database.inTransaction {
                songsToUpsert.chunked(BATCH_SIZE).forEach { chunk ->
                    val songs = chunk.map { it.entity }
                    songDao.upsertAll(songs)
                    if (indexLyrics) {
                        LyricFtsIndexer.replaceSongs(songDao, songs)
                    } else {
                        songDao.deleteLyricFtsByUris(songs.map { it.uri })
                    }
                    chunk.forEach { metadata ->
                        songCustomTagKeyDao.replaceForSong(
                            songUri = metadata.entity.uri,
                            keys = metadata.customFields.mapNotNull { field ->
                                field.key.trim().takeIf { it.isNotBlank() }?.uppercase()
                            }
                        )
                    }
                }

                lyricsToIndex.chunked(BATCH_SIZE).forEach { rows ->
                    rows.forEach { row ->
                        songDao.updateLyricSearchText(row.uri, row.lyricSearchText)
                    }
                    LyricFtsIndexer.replaceSongLyrics(songDao, rows)
                }

                allDeletedUris.chunked(BATCH_SIZE).forEach { chunk ->
                    songDao.deleteByUris(chunk.toList())
                    songDao.deleteLyricFtsByUris(chunk.toList())
                    songCustomTagKeyDao.deleteForSongs(chunk.toList())
                }

                unavailableFolderIds.forEach { folderId ->
                    folderDao.deleteFolderTreePermanently(folderId)
                }

                impactedFolderIds
                    .filterNot { it in unavailableFolderIds }
                    .forEach { folderId -> folderDao.refreshSongCount(folderId) }

                folderDao.performPostScanCleanup()
            }

            if (songsToUpsert.isNotEmpty()) {
                val indexedSongs = songDao.getSongsByUris(songsToUpsert.map { it.entity.uri })
                libraryIndexRepository.reindexSongs(indexedSongs)
            }
            if (allDeletedUris.isNotEmpty() || unavailableFolderIds.isNotEmpty()) {
                libraryIndexRepository.refreshAndPruneIndexes()
            }

            onProgress(
                LibraryScanProgress(
                    stage = LibraryScanStage.WRITING_DATABASE,
                    current = databaseChanges,
                    total = databaseChanges
                )
            )

            settingsRepository.saveLastScanTime(System.currentTimeMillis())
            val result = LibraryScanResult(
                scanned = deviceSongs.size,
                inserted = songsToUpsert.count { it.isInsert },
                updated = songsToUpsert.count { !it.isInsert },
                deleted = allDeletedUris.size,
                skipped = deviceSongs.size - songsToUpsert.size,
                failures = failures
            )
            logResult(request, result, failures.size, impactedFolderIds.size)
            onProgress(LibraryScanProgress(stage = LibraryScanStage.FINISHED))
            return result
        } catch (e: Exception) {
            logException("Library synchronization failed", e)
            throw e
        }
    }

    private suspend fun extractSongMetadata(
        songFile: SongFile,
        folderId: Long,
        existingId: Long,
        indexLyrics: Boolean,
        separator: String
    ): ScannedSongMetadata? {
        val audioData = audioTagRepository.read(
            uri = songFile.path.toString(),
            options = AudioTagReadOptions(multiValueSeparator = separator)
        )
        val entity = songMetadataMapper.fromScannedFile(
            file = songFile,
            tag = audioData,
            folderId = folderId,
            // Without the stored row id an @Upsert that hits the UNIQUE `uri` index degrades to
            // `UPDATE ... WHERE id = 0`, which changes nothing and swallows the conflict: the file
            // would be re-read on every scan and its tags never refreshed.
            existingId = existingId,
            source = SongSource.LOCAL,
            indexLyrics = indexLyrics
        )
        return ScannedSongMetadata(
            entity = entity,
            customFields = audioData.customFields,
            isInsert = existingId == 0L
        )
    }

    private suspend fun logResult(
        request: LibraryScanRequest,
        result: LibraryScanResult,
        folderFailures: Int,
        foldersUpdated: Int
    ) {
        try {
            appLogRepository.log(
                level = if (result.failures.isEmpty()) AppLogLevel.INFO else AppLogLevel.WARNING,
                type = AppLogType.APP,
                tag = TAG,
                message = "Library synchronization finished",
                detail = buildString {
                    appendLine("fullRescan=${request.fullRescan}")
                    appendLine("scanned=${result.scanned}")
                    appendLine("inserted=${result.inserted}")
                    appendLine("updated=${result.updated}")
                    appendLine("deleted=${result.deleted}")
                    appendLine("folderFailures=$folderFailures")
                    appendLine("foldersUpdated=$foldersUpdated")
                    appendLine("failures=${result.failures.size}")
                    appendLine("ignoreShortAudio=${request.ignoreShortAudio}")
                }
            )
        } catch (e: Exception) {
            PlatformLog.w(TAG, "Failed to write scan log", e)
        }
    }

    private suspend fun logException(message: String, throwable: Throwable) {
        try {
            appLogRepository.logException(
                type = AppLogType.APP,
                tag = TAG,
                message = message,
                throwable = throwable
            )
        } catch (e: Exception) {
            PlatformLog.w(TAG, "Failed to write scan exception log", e)
        }
    }

    private data class ScannedSongMetadata(
        val entity: SongEntity,
        val customFields: List<CustomTagField>,
        val isInsert: Boolean
    )

    private companion object {
        const val TAG = "LibraryScanRepository"
        const val BATCH_SIZE = 50
        const val MIN_DURATION_MILLIS = 60_000L
    }
}
