package com.lonx.lyrico.probe

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.tag.AudioTagFieldKey
import com.lonx.lyrico.data.song.tag.AudioTagMutation
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.song.tag.FieldMutation
import com.lonx.lyrico.data.utils.SongQueryBuilder
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.utils.LibraryScanManager
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortInfo
import com.lonx.lyrico.viewmodel.SortOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test

/**
 * Fills the development data folder with a real library, so a capture of the running window shows the
 * songs page with content instead of the empty state.
 *
 * This is a tool, not an assertion: it is the setup half of the real-window evidence in PLAN.md
 * (`scripts/capture-window.ps1` + `scripts/ocr-window-capture.ps1`), and it is skipped unless asked
 * for, because it rewrites the app's own database and preferences:
 *
 * ```
 * ./gradlew :lyrico-app:test --tests "*DevLibrarySeederTest*" -Plyrico.seedDevLibrary=1
 * ./gradlew :lyrico-app:run
 * powershell -File scripts/capture-window.ps1 -TitleLike "Lyrico 1.6" -OutputPath out.png
 * ```
 *
 * Everything it does goes through the real DI graph - TagLib writes the tags, the real scanner walks
 * the folder, Room stores the rows - so the screenshot shows what a user with these files would see.
 * The seeded files are copies of the TagLib fixtures with Chinese tags, which is why the capture also
 * exercises CJK rendering and the title-bar count.
 */
class DevLibrarySeederTest {

    private companion object {
        /** A message the failure rows can carry into the capture. */
        const val TAG_WRITE_ERROR = "标签写入失败：权限不足"

        /** The demo album added for the C6d window evidence (see [albums]). */
        const val DEMO_ALBUM_NAME = "演示合集"

        /** C6d: how many copies of the 3.55 s fixture the demo album gets (see [albums]). */
        const val DEMO_TRACK_COUNT = 20
    }

    private val fixtures = File(
        requireNotNull(System.getProperty("lyrico.audiotag.fixtures.dir")) { "fixtures dir property missing" }
    )

    /** Copies the fixtures into their album folders and writes the tags each row should show. */
    private val albums = listOf(
        "周华健 - 朋友" to listOf("bladeenc.mp3" to "朋友", "alaw.wav" to "花心"),
        "李宗盛 - 山丘" to listOf("silence-44-s.flac" to "山丘"),
        "Earth, Wind & Fire - September" to listOf("test.ogg" to "September"),
        // C6d: album ReplayGain's "calculating" is a transient state, and the three albums above hold
        // four short files, so a run finishes in about half a second -- too fast to photograph. This
        // album repeats the same 3.55 s fixture twenty times under twenty different titles, which makes
        // the measurement a real multi-track programme (the album loudness is still a duration-weighted
        // mean over genuinely measured tracks) that takes long enough to photograph: measured in the
        // window, the sheet reported `用时 1.04 秒` for all twenty tracks (52 ms each, which is ffmpeg's
        // own cost -- see `docs/port-evidence/c6d-album-replay-gain-tags.txt`). That is over the ~0.6 s
        // it takes to go from clicking the row to the first captured frame with `-SettleMs 0` on both
        // scripts, which is why the capture lands at 90% instead of on the finished sheet. It is added
        // in a way that leaves the other evidence reproducible: the batch tasks below take their songs
        // from `taskSongs`, which excludes this album, so the C6c captures still show the same file
        // names if the seeder is re-run.
        "Various Artists - $DEMO_ALBUM_NAME" to
            (1..DEMO_TRACK_COUNT).map { "bladeenc.mp3" to "演示曲目 %02d".format(it) },
    )

    @Test
    fun `seed the development library for a window capture`() {
        assumeTrue(
            "pass -Plyrico.seedDevLibrary=1 to seed the app's data folder for a real-window capture",
            System.getProperty("lyrico.seedDevLibrary") == "1",
        )

        runBlocking {
            val directories = AppDirectories.resolve()
            println("SEED dataDir=${directories.root}")

            // A fresh database, so the screenshot is deterministic instead of showing whatever a
            // previous run left behind.
            val database = File(directories.root, LyricoDatabase.DATABASE_FILE_NAME)
            listOf(database, File(database.path + "-shm"), File(database.path + "-wal"), File(database.path + ".lck"))
                .forEach { println("SEED deleting=${it.name} existed=${it.delete()}") }

            val library = File(System.getProperty("user.dir"), "build/demo-library")
            library.deleteRecursively()
            val paths = copyFixtures(library)
            println("SEED library=${library.absolutePath} files=${paths.size}")

            startKoin { modules(desktopAppModule(directories)) }
            try {
                val koin = GlobalContext.get()
                // The fixtures are all shorter than a minute, and the app's own default drops short
                // audio. Turning it off here is the same preference a user with short files sets, and
                // it is what makes the seeded rows appear at all.
                koin.get<SettingsRepository>().saveIgnoreShortAudio(false)
                writeTags(koin.get<AudioTagRepository>(), paths)
                koin.get<LibraryScanManager>().addFolderAndScan(library.absolutePath)

                val songs = waitForScan(koin.get<LyricoDatabase>())
                val folders = koin.get<LyricoDatabase>().folderDao().getAllFoldersOnce()
                println("SEED roots=${folders.map(FolderEntity::path)}")
                println("SEED songs=${songs.size}")
                songs.forEach {
                    println("SEED song uri=${it.uri} title=${it.title} artist=${it.artist} album=${it.album}")
                }

                // The batch task screens have no user-facing entry point yet, so the capture reaches
                // them through the dev start route and needs rows in the history. The four shapes below
                // cover the whole list-row vocabulary: a fresh task, a clean success, a partly failed
                // one, and a task that failed outright. The "fresh" one is left QUEUED, which is what a
                // just-created task looks like -- but note that no task survives an app start:
                // `markOrphanedTasksFailed()` treats RUNNING *and* QUEUED as orphans and rewrites them to
                // FAILED ("Task interrupted by system"), so the list window shows this row as failed
                // rather than with a cancel icon. That rewrite is real behaviour and its appearance in
                // the capture is the evidence for it; the live/cancel row is covered headlessly instead,
                // because there is no way to create a task while the window is open.
                val tasks = koin.get<BatchTaskRepository>()
                // The demo album is deliberately excluded here: the C6c captures document the file
                // names these four tasks put on screen, and the album above would otherwise reorder
                // `songs` and change them on a re-run.
                val taskSongs = songs.filterNot { it.album == DEMO_ALBUM_NAME }
                val queued = tasks.createTask(BatchTaskType.MATCH_COVER, taskSongs.take(2), null)
                val clean = seedTask(tasks, BatchTaskType.EDIT_TAGS, taskSongs.take(3), succeeded = 3)
                val mixed = seedTask(
                    tasks,
                    BatchTaskType.MATCH_LYRICS,
                    taskSongs,
                    succeeded = 2,
                    failed = 1,
                    skipped = 1,
                )
                val broken = seedTask(
                    tasks,
                    BatchTaskType.RENAME_FILES,
                    taskSongs.take(1),
                    succeeded = 0,
                    failed = 1,
                    failing = true,
                )
                println("SEED batchTasks queued=$queued clean=$clean mixed=$mixed broken=$broken")
            } finally {
                stopKoin()
            }
        }
    }

    private fun copyFixtures(library: File): Map<String, File> = buildMap {
        albums.forEach { (albumDir, files) ->
            files.forEachIndexed { index, (fixture, title) ->
                val extension = fixture.substringAfterLast('.')
                val target = File(library, "$albumDir/${index + 1} $title.$extension")
                target.parentFile!!.mkdirs()
                Files.copy(fixtures.resolve(fixture).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                put(title, target)
            }
        }
    }

    /** Tags come first, so the rows in the capture are the tags the app read, not the fixture's own. */
    private suspend fun writeTags(tags: AudioTagRepository, paths: Map<String, File>) {
        paths.forEach { (title, file) ->
            val albumDir = file.parentFile.name
            tags.patch(
                file.absolutePath,
                AudioTagMutation(
                    AudioTagMutationMode.Patch,
                    fields = mapOf(
                        AudioTagFieldKey.Title to FieldMutation.Set(title),
                        AudioTagFieldKey.Artist to FieldMutation.Set(albumDir.substringBefore(" - ")),
                        AudioTagFieldKey.Album to FieldMutation.Set(albumDir.substringAfter(" - ")),
                    ),
                ),
            )
        }
    }

    /**
     * A finished task whose counters come from real item rows, exactly as a worker would leave them.
     *
     * Items are addressed with the `<taskId>-<index>` scheme `createTask` assigns, and every status is
     * written through the repository call the worker uses, so the list's stat line and the detail's
     * three tabs are reading rows rather than injected numbers.
     */
    private suspend fun seedTask(
        tasks: BatchTaskRepository,
        type: BatchTaskType,
        songs: List<SongEntity>,
        succeeded: Int,
        failed: Int = 0,
        skipped: Int = 0,
        failing: Boolean = false,
    ): String {
        val taskId = tasks.createTask(type, songs.take(succeeded + failed + skipped), null)
        tasks.markRunning(taskId)
        var index = 0
        repeat(succeeded) { tasks.markItemSucceeded("$taskId-${index++}", null) }
        repeat(failed) { tasks.markItemFailed("$taskId-${index++}", TAG_WRITE_ERROR) }
        repeat(skipped) { tasks.markItemSkipped("$taskId-${index++}", null) }
        tasks.updateProgressFromItems(taskId, currentFile = null)
        if (failing) tasks.markFailed(taskId, TAG_WRITE_ERROR) else tasks.markSucceeded(taskId)
        return taskId
    }

    /** The scan runs in the background, so poll the same query the songs page observes. */
    private suspend fun waitForScan(database: LyricoDatabase): List<SongEntity> {
        repeat(120) {
            val songs = database.songDao()
                .getSongs(SongQueryBuilder.build(SortInfo(SortBy.TITLE, SortOrder.ASC)))
                .first()
            if (songs.size >= albums.sumOf { it.second.size }) return songs
            Thread.sleep(500)
        }
        error("the seeded scan did not reach the database")
    }
}
