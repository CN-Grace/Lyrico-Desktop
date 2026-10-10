package com.lonx.lyrico.worker

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.lyrics.LyricFormat
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRequest
import com.lonx.lyrico.data.song.tag.AudioTagFieldKey
import com.lonx.lyrico.data.song.tag.AudioTagMutation
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.song.tag.AudioTagWriteResult
import com.lonx.lyrico.data.song.tag.FieldMutation
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.viewmodel.LyricsFormatConfig
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortOrder
import com.lonx.lyrico.worker.processor.EditTagsTaskConfig
import com.lonx.lyrico.worker.processor.RenameFilesTaskConfig
import com.lonx.lyrico.worker.processor.RenameFilesTaskResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The C6a acceptance test: **a batch task, launched through the real dependency graph, changes real
 * files and real database rows.**
 *
 * Everything in the path is the shipped object. The processors are resolved from `desktopAppModule`
 * (not constructed by the test), the audio is TagLib's own fixture files on a real NTFS path, the
 * tags are read and written by the JNI/ TagLib layer, the rows come out of a real `lyrico.db`, and the
 * run is driven by the real [BatchTaskRunner] over the real [BatchTaskRepository].
 *
 * The two unit-test classes beside this one deliberately do the opposite: `BatchTaskRunnerTest` fakes
 * the *processor* so it can pin the engine's own bookkeeping (concurrency, counts, cancellation,
 * log detail) precisely, and `BatchTaskSchedulerTest` pins the queue. Neither of them proves that a
 * real processor, wired to a real `SaveAudioTagsUseCase`, actually reaches the disk. That is what
 * this class is for, and it is the seam where a port of this size usually breaks: a path passed as a
 * `String` where the Android code passed a `Uri`, an untranslated `Uri`/`Context` reference, a
 * callback that used to run on a worker thread, or a lyrics pipeline that reads the file's tag but
 * writes the row's copy.
 *
 * What it does not cover, on purpose: the three plugin-driven match processors (C6b), ReplayGain
 * (deferred to its own batch, it needs the ffmpeg sidecar) and the export processor (C6e, blocked by
 * `DocumentFile`). See PLAN.md for the inventory.
 */
class BatchTaskEngineEndToEndTest {

    private val dataRoot: File = Files.createTempDirectory("lyrico-batch-engine").toFile()
    private val directories = AppDirectories(root = dataRoot, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-batch-engine-library")

    private val json = Json { encodeDefaults = true }

    private lateinit var database: LyricoDatabase
    private lateinit var tasks: BatchTaskRepository
    private lateinit var runner: BatchTaskRunner
    private lateinit var songs: SongLibraryRepository
    private lateinit var tags: AudioTagRepository
    private lateinit var scanner: LibraryScanRepository
    private lateinit var appLog: AppLogRepository

    /** The library root row is inserted once per test, on the first scan. */
    private var rootAdded = false

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        val koin = GlobalContext.get()
        database = koin.get()
        tasks = koin.get()
        runner = koin.get()
        songs = koin.get()
        tags = koin.get()
        scanner = koin.get()
        appLog = koin.get()
        // TagLib's fixtures are all shorter than a minute and the app's own default drops short
        // audio, so a scan would (correctly) insert nothing. This is the preference a user with short
        // files turns off, saved through the real settings store; the scan request below repeats it
        // because the scanner takes the flag from the caller, not from the settings.
        runBlocking { koin.get<SettingsRepository>().saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        // Best effort: Room's connection pool and the preferences store may still hold the files open.
        dataRoot.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    @Test
    fun `an edit-tags task writes the tag to the file and to the row`() = runBlocking<Unit> {
        val song = placeSong(title = "Old Title", artist = "周华健", album = "朋友")

        val taskId = tasks.createTask(
            BatchTaskType.EDIT_TAGS,
            listOf(song),
            configJson(
                EditTagsTaskConfig.serializer(),
                EditTagsTaskConfig(title = "新标题", artist = "Wakin Chau", concurrency = 1),
            ),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)

        // The file, read back by a fresh TagLib read: what the user would see in another player.
        val written = tags.read(song.uri, AudioTagReadOptions())
        assertEquals("新标题", written.title)
        assertEquals("Wakin Chau", written.artist)

        // The row: `SaveAudioTagsUseCase` re-reads the file after writing it and stores that snapshot,
        // so the row can never drift from the file. The path is unchanged, so the row is the same one.
        val row = songs.getSongByUri(song.uri)
        assertNotNull(row, "the edit must update the scanned row, not insert a second one")
        assertEquals("新标题", row.title)
        assertEquals("Wakin Chau", row.artist)
        assertEquals(1, songs.getSongCount(), "one song in, one row out")

        val item = tasks.observeItems(taskId).first().single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status)
        assertNull(item.errorMessage)
        assertEquals(song.uri, item.songUri)
    }

    @Test
    fun `a rename task moves the file on disk and in the database`() = runBlocking<Unit> {
        val song = placeSong(fileName = "before.mp3", title = "朋友", artist = "周华健")

        val taskId = tasks.createTask(
            BatchTaskType.RENAME_FILES,
            listOf(song),
            configJson(RenameFilesTaskConfig.serializer(), RenameFilesTaskConfig(renameFormat = "@1 - @2")),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)

        // `@1` is the title and `@2` the artist, and no mapping rule is enabled, so the new name is
        // exactly the format, trimmed, with the original extension kept.
        val moved = musicDir.resolve("Album/朋友 - 周华健.mp3")
        assertTrue(Files.isRegularFile(moved), "the file must exist at its new name: $moved")
        assertFalse(Files.exists(Path.of(song.uri)), "the old name must be gone, not copied")

        val movedUri = moved.toRealPath().toString()
        assertNull(songs.getSongByUri(song.uri), "the row must follow the file, not be left behind")
        val row = songs.getSongByUri(movedUri)
        assertNotNull(row)
        assertEquals("朋友 - 周华健.mp3", row.fileName)
        assertEquals(movedUri, row.filePath)

        // The item records where the file went, so the (not yet ported) task detail screen can show it
        // and a later task can still find the song.
        val item = tasks.observeItems(taskId).first().single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status)
        assertEquals(movedUri, item.filePath)
        assertEquals("朋友 - 周华健.mp3", item.fileName)
        val result = json.decodeFromString(RenameFilesTaskResult.serializer(), item.resultJson!!)
        assertEquals(song.uri, result.originalUri)
        assertEquals(movedUri, result.newUri)
    }

    @Test
    fun `a lyrics-format task rewrites the LRC in the file as TTML`() = runBlocking<Unit> {
        val song = placeSong(
            title = "歌词测试",
            artist = "测试歌手",
            lyrics = "[00:01.000]Original line\n[00:01.000]翻译行",
        )
        val scannedLyrics = song.lyrics
        assertNotNull(scannedLyrics, "precondition: the scan read the lyrics out of the file's tag")
        assertTrue(scannedLyrics.contains("Original line"), "precondition: the LRC survived the round trip")

        val taskId = tasks.createTask(
            BatchTaskType.CONVERT_LYRICS_FORMAT,
            listOf(song),
            configJson(
                LyricsFormatConfig.serializer(),
                // `formatLineOrder = false` is what makes this a format conversion rather than a
                // two-column re-sort; it is the flag the shipping UI sets for a target format.
                LyricsFormatConfig(targetFormat = LyricFormat.TTML, concurrency = 1, formatLineOrder = false),
            ),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)

        // On disk: the LRC was upgraded in the tag, with the second line linked as the translation.
        val converted = tags.read(song.uri, AudioTagReadOptions()).lyrics
        assertNotNull(converted)
        assertTrue(converted.contains("""itunes:key="L1""""), "expected TTML on disk, got:\n$converted")
        assertTrue(converted.contains("""<text for="L1">翻译行</text>"""), "the translation must survive")
        assertTrue(converted.contains("Original line"))

        // In the row: the lyrics column holds the converted text, and the source path is unchanged.
        val row = songs.getSongByUri(song.uri)
        assertNotNull(row)
        assertEquals(converted, row.lyrics, "the row must store what the file now holds")
        assertEquals(scannedLyrics, song.lyrics, "the row the task was created from is a snapshot")

        val item = tasks.observeItems(taskId).first().single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status)
    }

    @Test
    fun `three files are renamed at once and every one lands`() = runBlocking<Unit> {
        val placed = listOf(
            placeFile(fileName = "one.mp3", title = "第一首", artist = "歌手甲"),
            placeFile(fileName = "two.mp3", title = "第二首", artist = "歌手乙"),
            placeFile(fileName = "three.mp3", title = "第三首", artist = "歌手丙"),
        )
        val uris = placed.map { it.toRealPath().toString() }
        val scanned = scanLibrary()
        assertEquals(uris.sorted(), scanned.map { it.uri }.sorted(), "precondition: three rows for three files")

        // `RenameFilesTaskConfig` carries no `concurrency`, so the runner's own default (3) applies:
        // the three renames really do run at once, each with its own tag read and its own row update.
        val taskId = tasks.createTask(
            BatchTaskType.RENAME_FILES,
            scanned,
            configJson(RenameFilesTaskConfig.serializer(), RenameFilesTaskConfig(renameFormat = "@1 - @2")),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)
        val items = tasks.observeItems(taskId).first()
        assertEquals(3, items.size)
        assertTrue(
            items.all { it.status == BatchTaskStatus.SUCCEEDED },
            "every item must succeed, got: ${items.map { it.fileName to it.status }}",
        )

        val expected = mapOf(
            "one.mp3" to "第一首 - 歌手甲.mp3",
            "two.mp3" to "第二首 - 歌手乙.mp3",
            "three.mp3" to "第三首 - 歌手丙.mp3",
        )
        uris.forEach { uri ->
            val original = Path.of(uri)
            val newName = expected.getValue(original.fileName.toString())
            val renamed = musicDir.resolve("Album/$newName")
            assertTrue(Files.isRegularFile(renamed), "missing $renamed")
            assertFalse(Files.exists(original), "the original must be gone: $original")
            val row = songs.getSongByUri(renamed.toRealPath().toString())
            assertNotNull(row, "no row for $newName")
            assertEquals(newName, row.fileName)
        }
        assertEquals(3, songs.getSongCount(), "three renames must not duplicate or drop a row")
    }

    @Test
    fun `a file that disappeared fails its own item and leaves the run finished`() = runBlocking<Unit> {
        val kept = placeFile(fileName = "kept.mp3", title = "留下", artist = "歌手")
        val lost = placeFile(fileName = "lost.mp3", title = "失踪", artist = "歌手")
        val keptUri = kept.toRealPath().toString()
        val lostUri = lost.toRealPath().toString()
        val scanned = scanLibrary()
        assertEquals(setOf(keptUri, lostUri), scanned.map { it.uri }.toSet())

        val taskId = tasks.createTask(
            BatchTaskType.RENAME_FILES,
            scanned,
            configJson(RenameFilesTaskConfig.serializer(), RenameFilesTaskConfig(renameFormat = "@1 - @2")),
        )
        // The file goes away between creating the task and running it - the desktop equivalent of a
        // file the user moved out from under the library, or a drive that is no longer mounted.
        Files.delete(Path.of(lostUri))
        runner.run(taskId)

        val items = tasks.observeItems(taskId).first().associateBy { it.songUri }
        assertEquals(BatchTaskStatus.SUCCEEDED, items.getValue(keptUri).status)
        val failed = items.getValue(lostUri)
        assertEquals(BatchTaskStatus.FAILED, failed.status)
        assertNotNull(failed.errorMessage, "a failed item has to carry a reason")

        // The run itself still finishes: one bad file is not a reason to strand the task row, and the
        // engine books it as a warning so the user is told rather than left with a half-done task.
        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)
        val finished = appLog.observeByRelatedId(taskId).first()
            .single { it.message.contains("Batch task finished") }
        assertEquals(AppLogLevel.WARNING, finished.level)
        assertEquals(AppLogType.BATCH, finished.type)
        val detail = finished.detail
        assertNotNull(detail)
        assertTrue(detail.contains("failure=1"), "the summary must count the failure")
        assertTrue(detail.contains("success=1"))
    }

    @Test
    fun `a finished run is logged through the real app log repository`() = runBlocking<Unit> {
        val song = placeSong(title = "日志", artist = "歌手")

        val taskId = tasks.createTask(
            BatchTaskType.EDIT_TAGS,
            listOf(song),
            configJson(EditTagsTaskConfig.serializer(), EditTagsTaskConfig(album = "日志专辑", concurrency = 1)),
        )
        runner.run(taskId)

        val entry = appLog.observeByRelatedId(taskId).first()
            .single { it.message.contains("Batch task finished") }
        assertEquals(AppLogLevel.INFO, entry.level)
        assertEquals(AppLogType.BATCH, entry.type)
        assertEquals(taskId, entry.relatedId)
        assertTrue(entry.message.contains("EDIT_TAGS"), entry.message)
        val detail = entry.detail
        assertNotNull(detail)
        assertTrue(detail.contains("total=1"), "the log carries the run summary the UI shows")
        assertTrue(detail.contains("success=1"))
        assertTrue(detail.contains("album=日志专辑"), "the summary names what the task was asked to do")
    }

    // ---------------------------------------------------------------- helpers

    /** Copies a real audio fixture out of TagLib's vendored test files into the temp library. */
    private fun placeFile(
        fileName: String,
        title: String,
        artist: String,
        album: String? = null,
        lyrics: String? = null,
        fixture: String = "bladeenc.mp3",
    ): Path {
        val source = Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), fixture)
        assertTrue(Files.isRegularFile(source), "missing audio fixture $source")
        val folder = musicDir.resolve("Album")
        Files.createDirectories(folder)
        val target = folder.resolve(fileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

        val fields = buildMap {
            put(AudioTagFieldKey.Title, FieldMutation.Set(title))
            put(AudioTagFieldKey.Artist, FieldMutation.Set(artist))
            if (album != null) {
                put(AudioTagFieldKey.Album, FieldMutation.Set(album))
                put(AudioTagFieldKey.AlbumArtist, FieldMutation.Set(artist))
            }
            if (lyrics != null) put(AudioTagFieldKey.Lyrics, FieldMutation.Set(lyrics))
        }
        val result = runBlocking {
            tags.patch(target.toString(), AudioTagMutation(mode = AudioTagMutationMode.Patch, fields = fields))
        }
        assertIs<AudioTagWriteResult.Success>(result, "the fixture has to be taggable")
        return target
    }

    /** Places one file and returns the row the following scan produced for it. */
    private suspend fun placeSong(
        title: String,
        artist: String,
        album: String? = null,
        lyrics: String? = null,
        fileName: String = "song.mp3",
    ): SongEntity {
        val path = placeFile(fileName = fileName, title = title, artist = artist, album = album, lyrics = lyrics)
        val uri = path.toRealPath().toString()
        return scanLibrary().single { it.uri == uri }
    }

    /**
     * Scans the library the way the app does - through the real scanner, reading real tags into the
     * real database - and returns the rows it produced.
     *
     * The scan is called directly rather than through `LibraryScanManager`, because the manager runs
     * on the app's own scope and only reports progress: a test would have to poll the database and
     * hope. `synchronize` is the same call the manager makes, awaited.
     */
    private suspend fun scanLibrary(): List<SongEntity> {
        if (!rootAdded) {
            database.folderDao().insert(
                FolderEntity(path = musicDir.toRealPath().toString(), addedBySaf = true)
            )
            rootAdded = true
        }
        val result = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertTrue(result.failures.isEmpty(), "the scan itself must not fail: ${result.failures}")
        return songs.observeSongs(SortBy.TITLE, SortOrder.ASC).first()
    }

    private fun <T> configJson(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)
}
