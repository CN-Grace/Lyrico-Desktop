package com.lonx.lyrico.probe

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
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

    private val fixtures = File(
        requireNotNull(System.getProperty("lyrico.audiotag.fixtures.dir")) { "fixtures dir property missing" }
    )

    /** Copies the fixtures into their album folders and writes the tags each row should show. */
    private val albums = listOf(
        "周华健 - 朋友" to listOf("bladeenc.mp3" to "朋友", "alaw.wav" to "花心"),
        "李宗盛 - 山丘" to listOf("silence-44-s.flac" to "山丘"),
        "Earth, Wind & Fire - September" to listOf("test.ogg" to "September"),
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
