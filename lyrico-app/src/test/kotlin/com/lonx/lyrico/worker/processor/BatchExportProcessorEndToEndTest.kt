package com.lonx.lyrico.worker.processor

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.ExportDestination
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
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
import com.lonx.lyrico.data.song.tag.FieldMutation
import com.lonx.lyrico.data.song.tag.PictureSource
import com.lonx.lyrico.data.song.tag.PictureUpdate
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortOrder
import com.lonx.lyrico.worker.BatchTaskRunner
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C6e 的最后一层验收：**一批导出真的在磁盘上留下了用户要的文件。**
 *
 * 上面几层（配置解码、文件名、格式判定）都可以用假的依赖测，这一层不行：真实的 Koin 图
 * （`desktopAppModule`）、真实的 Room 数据库、真实的库扫描、真实的 TagLib 读回，由真实的
 * [BatchTaskRunner] 驱动，断言的是磁盘上的字节而不是处理器的返回值。fixture 用的是 TagLib 自己的
 * 测试文件（真的 mp3/flac，真的能写标签），所以“歌词从标签里读出来、原样写进 .lrc”这句话是真的走完了
 * 全程。
 *
 * 有意不覆盖：发起界面（十二动作 FAB、两个 bottom sheet、`BatchExportViewModel`）—— 它们的宿主是
 * `ui/components/bar/SongSelectionSupport.kt`，跟着 #35 走；界面的发起路径由真窗口取证负责（见
 * PLAN.md 的 C6e 段）。
 */
class BatchExportProcessorEndToEndTest {

    private val dataRoot = Files.createTempDirectory("lyrico-export-processor").toFile()
    private val directories = AppDirectories(root = dataRoot, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-export-music")

    /** Where `SELECTED_DIRECTORY` points: a real folder, created before the run like the picker would. */
    private val exportDir: Path = Files.createTempDirectory("lyrico-export-target")

    /** The seed for `encodeDefaults` -- the launch screens write every field, so the tests do too. */
    private val json = Json { encodeDefaults = true }

    private lateinit var database: LyricoDatabase
    private lateinit var tasks: BatchTaskRepository
    private lateinit var runner: BatchTaskRunner
    private lateinit var songs: SongLibraryRepository
    private lateinit var tags: AudioTagRepository
    private lateinit var scanner: LibraryScanRepository
    private lateinit var settings: SettingsRepository

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
        settings = koin.get()
        // The app's default drops short audio; the fixtures are seconds long (bladeenc.mp3 is 3.55 s),
        // and a scan that inserts nothing would make every test below pass for the wrong reason.
        runBlocking { settings.saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        dataRoot.deleteRecursively()
        musicDir.toFile().deleteRecursively()
        exportDir.toFile().deleteRecursively()
    }

    // ------------------------------------------------------------------ lyrics into a chosen folder

    @Test
    fun `exporting lyrics writes the tag text into an lrc file in the chosen folder`() = runBlocking<Unit> {
        val lyrics = "[00:01.00]朋友一生一起走\n[00:05.00]那些日子不再有"
        val audio = placeSong("朋友.mp3", lyrics = lyrics)
        val originalBytes = Files.readAllBytes(audio)
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }

        val taskId = createTask(BatchTaskType.EXPORT_LYRICS, listOf(song))
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)
        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status)
        assertNull(item.errorMessage)

        // The bytes on disk, not the processor's idea of them: no BOM, no added newline, UTF-8 so a
        // CJK lyric sheet opens in a player that assumes UTF-8.
        val exported = exportDir.resolve("朋友.lrc")
        assertTrue(Files.isRegularFile(exported), "expected the export at $exported")
        assertEquals(lyrics, Files.readString(exported))
        assertTrue(lyrics.toByteArray(Charsets.UTF_8).contentEquals(Files.readAllBytes(exported)))

        // The row the batch detail screen renders: the detail card's title and summary are these two.
        assertEquals("朋友.lrc", item.fileName)
        assertEquals(exported.toAbsolutePath().toString(), item.filePath)

        // Exporting reads the song and writes next to it; neither the audio nor the library changes.
        assertTrue(originalBytes.contentEquals(Files.readAllBytes(audio)), "the audio file is not a scratch pad")
        assertEquals(1, songs.getSongCount())
        assertEquals(lyrics, tags.read(song.uri, AudioTagReadOptions(strict = true)).lyrics)
    }

    @Test
    fun `ttml lyrics keep their format in the file name`() = runBlocking<Unit> {
        // The shape the lyrics-format converter and the online sources produce for karaoke timing.
        val ttml = """<?xml version="1.0" encoding="utf-8"?>
            |<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            |<p begin="00:00:01.000" end="00:00:03.000">朋友</p>
            |</div></body></tt>""".trimMargin()
        placeSong("朋友.mp3", lyrics = ttml)
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }

        val taskId = createTask(BatchTaskType.EXPORT_LYRICS, listOf(song))
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, items(taskId).single().status)
        assertEquals(ttml, Files.readString(exportDir.resolve("朋友.ttml")))
        assertFalse(Files.exists(exportDir.resolve("朋友.lrc")), "a TTML sheet must not be named .lrc")
    }

    @Test
    fun `a song without lyrics is skipped and the run still finishes`() = runBlocking<Unit> {
        placeSong("朋友.mp3", lyrics = "[00:01.00]有歌词")
        placeSong("花心.flac", fixture = "silence-44-s.flac")
        val scanned = scanLibrary()
        val withLyrics = scanned.single { it.fileName == "朋友.mp3" }
        val withoutLyrics = scanned.single { it.fileName == "花心.flac" }

        val taskId = createTask(BatchTaskType.EXPORT_LYRICS, listOf(withLyrics, withoutLyrics))
        runner.run(taskId)

        // A skip is not a failure: the task succeeds and the row's stat line reads 成功 1 / 失败 0 / 跳过 1,
        // which is the one signature the window shows that only a real run through the processor can
        // produce (`BatchTaskSkippedException` is thrown from inside it).
        val task = assertNotNull(tasks.getTask(taskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, task.status)
        assertEquals(1, task.successCount)
        assertEquals(0, task.failureCount)
        assertEquals(1, task.skippedCount)

        val byStatus = items(taskId).associateBy { it.fileName }
        assertEquals(BatchTaskStatus.SUCCEEDED, byStatus.getValue("朋友.lrc").status)
        val skipped = byStatus.getValue("花心.flac")
        assertEquals(BatchTaskStatus.SKIPPED, skipped.status)
        assertEquals("No lyrics", skipped.resultJson)
        assertNull(skipped.errorMessage, "a skip carries a reason, not an error")

        assertTrue(Files.isRegularFile(exportDir.resolve("朋友.lrc")))
        assertEquals(
            listOf("朋友.lrc"),
            Files.list(exportDir).use { stream -> stream.map { it.fileName.toString() }.toList() },
            "only the song with lyrics may produce a file",
        )
    }

    @Test
    fun `exporting twice replaces the file instead of writing a second copy`() = runBlocking<Unit> {
        placeSong("朋友.mp3", lyrics = "[00:01.00]第一版")
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }

        val first = createTask(BatchTaskType.EXPORT_LYRICS, listOf(song))
        runner.run(first)
        assertEquals("[00:01.00]第一版", Files.readString(exportDir.resolve("朋友.lrc")))

        // The user edits the lyrics and exports again. Android's `findFile`-then-write and the desktop's
        // CREATE/TRUNCATE_EXISTING both mean the same thing here: the same file, with the new text, and
        // no `朋友 (1).lrc` next to it.
        tags.patch(
            song.uri,
            AudioTagMutation(
                mode = AudioTagMutationMode.Patch,
                fields = mapOf(AudioTagFieldKey.Lyrics to FieldMutation.Set("[00:01.00]第二版")),
            ),
        )
        val second = createTask(BatchTaskType.EXPORT_LYRICS, listOf(song))
        runner.run(second)

        assertEquals("[00:01.00]第二版", Files.readString(exportDir.resolve("朋友.lrc")))
        assertEquals(
            listOf("朋友.lrc"),
            Files.list(exportDir).use { stream -> stream.map { it.fileName.toString() }.toList() },
            "the second run must not leave a 朋友 (1).lrc behind",
        )
    }

    // ------------------------------------------------------------------ lyrics next to the audio

    @Test
    fun `exporting into the audio directory writes a sibling and leaves the audio untouched`() = runBlocking<Unit> {
        val lyrics = "[00:01.00]邻居"
        val audio = placeSong("朋友.mp3", lyrics = lyrics)
        val before = Files.readAllBytes(audio)
        val modifiedBefore = Files.getLastModifiedTime(audio)
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }

        val taskId = createTask(
            type = BatchTaskType.EXPORT_LYRICS,
            songs = listOf(song),
            destination = ExportDestination.AUDIO_DIRECTORY,
        )
        runner.run(taskId)

        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status, "the sibling write has to succeed: ${item.errorMessage}")
        val sibling = musicDir.resolve("朋友.lrc")
        assertEquals(lyrics, Files.readString(sibling))
        assertEquals(sibling.toAbsolutePath().toString(), item.filePath)

        // A media file the user's other tools also open: its bytes and its timestamp stay put.
        assertTrue(before.contentEquals(Files.readAllBytes(audio)))
        assertEquals(modifiedBefore, Files.getLastModifiedTime(audio))
    }

    @Test
    fun `two files sharing a base name export to one sibling file`() = runBlocking<Unit> {
        // The case the processor's mutex exists for: one folder holding 朋友.mp3 and 朋友.flac resolves
        // both items to the same 朋友.lrc. Serialized writes leave one of the two texts intact; two
        // interleaved writers could leave neither.
        placeSong("朋友.mp3", lyrics = "[00:01.00]mp3 的歌词")
        placeSong("朋友.flac", fixture = "silence-44-s.flac", lyrics = "[00:01.00]flac 的歌词")
        val scanned = scanLibrary()

        val taskId = createTask(
            type = BatchTaskType.EXPORT_LYRICS,
            songs = scanned,
            destination = ExportDestination.AUDIO_DIRECTORY,
        )
        runner.run(taskId)

        assertEquals(2, items(taskId).count { it.status == BatchTaskStatus.SUCCEEDED })
        val written = Files.readString(musicDir.resolve("朋友.lrc"))
        assertTrue(
            written == "[00:01.00]mp3 的歌词" || written == "[00:01.00]flac 的歌词",
            "one of the two lyrics files has to survive whole, got: $written",
        )
    }

    @Test
    fun `a requested song that is gone fails the item`() = runBlocking<Unit> {
        placeSong("朋友.mp3", lyrics = "[00:01.00]还在")
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }
        Files.delete(musicDir.resolve("朋友.mp3"))

        val taskId = createTask(BatchTaskType.EXPORT_LYRICS, listOf(song))
        runner.run(taskId)

        // The tag reader is strict, so a vanished file is a failure with the reader's own message, not a
        // silent empty export.
        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.FAILED, item.status)
        assertContains(assertNotNull(item.errorMessage), "Unable to read audio metadata")
        assertFalse(Files.exists(exportDir.resolve("朋友.lrc")), "nothing may be written for a missing song")

        // One bad item does not fail the run: the task row stays SUCCEEDED and counts the failure, the
        // app log says "finished_with_errors", and the detail screen shows 失败 1. Same accounting as
        // Android's worker, which returned success once it had walked the item list.
        val task = assertNotNull(tasks.getTask(taskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, task.status)
        assertEquals(0, task.successCount)
        assertEquals(1, task.failureCount)
    }

    // ------------------------------------------------------------------ covers

    @Test
    fun `exporting a cover writes the embedded bytes unchanged`() = runBlocking<Unit> {
        val cover = onePixelJpeg()
        placeSong("朋友.mp3", cover = cover)
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }
        assertTrue(
            tags.read(song.uri, AudioTagReadOptions(strict = true)).pictures.isNotEmpty(),
            "precondition: the fixture carries the cover the export should write out",
        )

        val taskId = createTask(BatchTaskType.EXPORT_COVER, listOf(song))
        runner.run(taskId)

        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status, "the cover export has to succeed: ${item.errorMessage}")
        // Android always named the cover `.jpg` and wrote whatever bytes the tag held; the port keeps
        // that, so the assertion is on the bytes -- the file is a picture the user's player can read
        // because the tag's picture is one.
        val exported = exportDir.resolve("朋友.jpg")
        assertEquals("朋友.jpg", item.fileName)
        assertTrue(cover.contentEquals(Files.readAllBytes(exported)), "the cover has to be the tag's own bytes")
    }

    @Test
    fun `a song without a cover is skipped`() = runBlocking<Unit> {
        placeSong("朋友.mp3")
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }
        assertEquals(0, tags.read(song.uri, AudioTagReadOptions()).pictures.size, "precondition: no cover")

        val taskId = createTask(BatchTaskType.EXPORT_COVER, listOf(song))
        runner.run(taskId)

        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.SKIPPED, item.status)
        assertEquals("No cover", item.resultJson)
        assertFalse(Files.exists(exportDir.resolve("朋友.jpg")))
    }

    // ------------------------------------------------------------------ destinations and names

    @Test
    fun `a destination that does not exist fails the item with the path it probed`() = runBlocking<Unit> {
        placeSong("朋友.mp3", lyrics = "[00:01.00]无家可归")
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }
        val missing = exportDir.resolve("已删除的导出目录")

        val taskId = createTask(BatchTaskType.EXPORT_LYRICS, listOf(song), directory = missing)
        runner.run(taskId)

        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.FAILED, item.status)
        val message = assertNotNull(item.errorMessage)
        assertContains(message, "Destination folder unavailable")
        // Android's sentence is kept, and the path it checked is appended: "unavailable" without saying
        // where is not something a user can fix.
        assertContains(message, missing.toAbsolutePath().toString())
        assertFalse(Files.exists(missing), "the processor must not create the folder it was pointed at")
    }

    @Test
    fun `a destination that is a file fails the item`() = runBlocking<Unit> {
        placeSong("朋友.mp3", lyrics = "[00:01.00]目标是个文件")
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }
        val notADirectory = exportDir.resolve("这是个文件.txt")
        Files.writeString(notADirectory, "not a folder")

        val taskId = createTask(BatchTaskType.EXPORT_LYRICS, listOf(song), directory = notADirectory)
        runner.run(taskId)

        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.FAILED, item.status)
        assertContains(assertNotNull(item.errorMessage), "Destination folder unavailable")
    }

    @Test
    fun `a task without a config is skipped`() = runBlocking<Unit> {
        placeSong("朋友.mp3", lyrics = "[00:01.00]配置丢了")
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }

        // `createTask` itself is the caller that omits the config (a task queued by an older build, or a
        // row whose config was cleared), so this goes around the helper's own default-config branch.
        val taskId = tasks.createTask(BatchTaskType.EXPORT_LYRICS, listOf(song), null)
        runner.run(taskId)

        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.SKIPPED, item.status)
        assertEquals("No config", item.resultJson)
        assertEquals(1, assertNotNull(tasks.getTask(taskId)).skippedCount)
    }

    @Test
    fun `an android tree uri row still exports next to the audio`() = runBlocking<Unit> {
        // A row carried over from Android: `destinationTreeUri` is not a field of the desktop config
        // (the picker hands back a plain path), so the decode is lenient. AUDIO_DIRECTORY never read
        // that field, which is exactly the case the leniency exists for.
        placeSong("朋友.mp3", lyrics = "[00:01.00]从安卓来的任务")
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }

        val taskId = createTask(
            type = BatchTaskType.EXPORT_LYRICS,
            songs = listOf(song),
            configJson = """
                {"destinationTreeUri":"content://com.android.externalstorage.documents/tree/primary%3AMusic",
                 "destination":"AUDIO_DIRECTORY","concurrency":1}
            """.trimIndent(),
        )
        runner.run(taskId)

        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status, "an AUDIO_DIRECTORY row must keep working: ${item.errorMessage}")
        assertEquals("[00:01.00]从安卓来的任务", Files.readString(musicDir.resolve("朋友.lrc")))
    }

    @Test
    fun `an android selected directory row fails instead of writing somewhere else`() = runBlocking<Unit> {
        // The other half of the same migration: a content URI is not a path, so the item fails with the
        // same message a missing folder produces. Falling back to the audio directory would scatter
        // files into the library without being asked.
        placeSong("朋友.mp3", lyrics = "[00:01.00]不该写到别处")
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }

        val taskId = createTask(
            type = BatchTaskType.EXPORT_LYRICS,
            songs = listOf(song),
            configJson = """{"destination":"SELECTED_DIRECTORY","concurrency":1}""",
        )
        runner.run(taskId)

        val item = items(taskId).single()
        assertEquals(BatchTaskStatus.FAILED, item.status)
        assertContains(assertNotNull(item.errorMessage), "Destination folder unavailable")
        assertFalse(Files.exists(musicDir.resolve("朋友.lrc")))
    }

    @Test
    fun `only the last extension is dropped from the exported name`() = runBlocking<Unit> {
        placeSong("Mr.Children - 花.mp3", lyrics = "[00:01.00]点名")
        val song = scanLibrary().single { it.fileName == "Mr.Children - 花.mp3" }

        val taskId = createTask(BatchTaskType.EXPORT_LYRICS, listOf(song))
        runner.run(taskId)

        // `substringBeforeLast` on the song file name: every dot that is not the extension stays.
        assertEquals(BatchTaskStatus.SUCCEEDED, items(taskId).single().status)
        assertTrue(Files.isRegularFile(exportDir.resolve("Mr.Children - 花.lrc")))
    }

    @Test
    fun `a song file without an extension keeps its whole name`() = runBlocking<Unit> {
        // Not reachable through the library (the scanner only indexes known audio extensions), so the
        // item row is built here: the processor reads the name it is handed, not the file system.
        val audio = placeSong("朋友.mp3", lyrics = "[00:01.00]没有扩展名")
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }

        val task = assertNotNull(tasks.getTask(createTask(BatchTaskType.EXPORT_LYRICS, listOf(song))))
        val item = items(task.taskId).single().copy(fileName = "朋友")

        val result = processor().process(task, item) {}
        assertEquals("朋友.lrc", result.updatedFileName)
        assertEquals("[00:01.00]没有扩展名", Files.readString(exportDir.resolve("朋友.lrc")))
        assertTrue(Files.readAllBytes(audio).isNotEmpty())
    }

    @Test
    fun `an unsupported task type is refused`() = runBlocking<Unit> {
        placeSong("朋友.mp3", lyrics = "[00:01.00]类型不对")
        val song = scanLibrary().single { it.fileName == "朋友.mp3" }
        val task = assertNotNull(tasks.getTask(createTask(BatchTaskType.EXPORT_LYRICS, listOf(song))))
        val item = items(task.taskId).single()
        val wrongType = task.copy(type = BatchTaskType.RENAME_FILES)

        // The runner would turn this into a failed item; the message is what the user reads, so it names
        // the type that was handed in.
        val failure = assertFailsWith<IllegalArgumentException> { processor().process(wrongType, item) {} }
        assertContains(assertNotNull(failure.message), "RENAME_FILES")
        assertFalse(Files.exists(exportDir.resolve("朋友.lrc")))
    }

    // -------------------------------------------------------------------------------- helpers

    /** The processor under test: the real one from the graph, with the real tag reader behind it. */
    private fun processor(): BatchTaskProcessor = BatchExportProcessor(tags)

    /**
     * Copies a TagLib fixture into the library under [fileName] and writes the tags the export reads.
     *
     * Fixtures rather than synthesised audio: the export reads tags, and a hand-written WAV is a poor
     * carrier for a picture or a USLT frame. `bladeenc.mp3` and `silence-44-s.flac` are the two the tag
     * tests and the native smoke harness also use.
     */
    private fun placeSong(
        fileName: String,
        lyrics: String? = null,
        cover: ByteArray? = null,
        fixture: String = "bladeenc.mp3"
    ): Path {
        val fixtures = Path.of(
            requireNotNull(System.getProperty("lyrico.audiotag.fixtures.dir")) { "fixtures dir property missing" }
        )
        val target = musicDir.resolve(fileName)
        Files.copy(fixtures.resolve(fixture), target, StandardCopyOption.REPLACE_EXISTING)

        val fields = buildMap {
            put(AudioTagFieldKey.Title, FieldMutation.Set(fileName.substringBeforeLast('.')))
            put(AudioTagFieldKey.Artist, FieldMutation.Set("测试艺人"))
            put(AudioTagFieldKey.Album, FieldMutation.Set("测试专辑"))
            lyrics?.let { put(AudioTagFieldKey.Lyrics, FieldMutation.Set(it)) }
        }
        runBlocking {
            tags.patch(
                target.toAbsolutePath().toString(),
                AudioTagMutation(
                    mode = AudioTagMutationMode.Patch,
                    fields = fields,
                    pictureUpdate = cover
                        ?.let { PictureUpdate.ReplaceFrontCover(PictureSource.Bytes(it, mimeType = "image/jpeg")) }
                        ?: PictureUpdate.Unchanged,
                ),
            )
        }
        return target
    }

    /** The scan the app's own manager performs, awaited: real tags read into the real database. */
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

    /**
     * Creates the batch task the (not yet ported) export screen creates: the config carries the mode,
     * the folder the picker returned, and the concurrency the engine uses.
     */
    private suspend fun createTask(
        type: BatchTaskType,
        songs: List<SongEntity>,
        destination: ExportDestination = ExportDestination.SELECTED_DIRECTORY,
        directory: Path? = exportDir,
        configJson: String? = null,
    ): String = tasks.createTask(
        type,
        songs,
        configJson ?: json.encodeToString(
            BatchExportTaskConfig.serializer(),
            BatchExportTaskConfig(
                destinationDirectory = directory?.toAbsolutePath()?.toString(),
                destination = destination,
                concurrency = 1,
            ),
        ),
    )

    private suspend fun items(taskId: String): List<BatchTaskItemEntity> =
        tasks.observeItems(taskId).first()

    /** A real JPEG, so the `.jpg` the processor names carries bytes a picture viewer can open. */
    private fun onePixelJpeg(): ByteArray = ByteArrayOutputStream().also { out ->
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", out)
    }.toByteArray()
}
