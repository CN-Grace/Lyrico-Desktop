package com.lonx.lyrico.data.song.scan

import com.lonx.lyrico.data.model.entity.FolderEntity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the filesystem walk that feeds the database.
 *
 * The walk runs against a **real temporary directory tree** wherever the point of the test is what
 * gets found (extensions, hidden files, folder names, canonical paths), and against a **fake
 * filesystem** where the point is failure: making a real directory unreadable on Windows needs ACL
 * work, and a test that silently passes because the ACL did not stick is worse than no test. The
 * fake is the reason [SongFileSystem] exists.
 */
class MediaScannerTest {

    private lateinit var workingDir: Path

    @BeforeTest
    fun setUp() {
        workingDir = Files.createTempDirectory("lyrico-media-scanner")
    }

    @AfterTest
    fun tearDown() {
        workingDir.toFile().deleteRecursively()
    }

    @Test
    fun `finds supported audio files and records their canonical paths`() {
        writeFile("Album One/Track One.mp3")
        writeFile("Album One/Track Two.FLAC")
        writeFile("Album One/notes.txt")
        writeFile("loose.m4a")

        val root = folder(workingDir)
        val result = MediaScanner().scan(listOf(root))

        assertEquals(setOf(root.id), result.successfulFolderIds)
        assertTrue(result.missingFolderIds.isEmpty())
        assertTrue(result.failedFolderIds.isEmpty())
        assertEquals(
            listOf("loose.m4a", "Track One.mp3", "Track Two.FLAC"),
            result.songs.map { it.songFile.fileName }.sortedBy { it.lowercase() },
        )

        val track = result.songs.single { it.songFile.fileName == "Track One.mp3" }
        val realFile = workingDir.resolve("Album One/Track One.mp3").toRealPath()
        assertEquals(realFile, track.songFile.path, "the scanned path is the canonical file path")
        assertEquals(realFile.toString(), track.songFile.filePath)
        assertEquals(realFile.parent.toString(), track.folderPath, "folderPath is the parent directory")
        assertEquals(root.id, track.rootFolderId)
        assertEquals(Files.size(realFile), track.songFile.fileSize)
        assertTrue(track.songFile.mediaId < 0L, "mediaId keeps Android's virtual-id convention")
    }

    @Test
    fun `ignores hidden entries and windows system folders but keeps ordinary folder names`() {
        writeFile(".hidden/song.mp3")
        writeFile(".hidden-file.mp3")
        writeFile("\$RECYCLE.BIN/song.mp3")
        writeFile("System Volume Information/song.mp3")
        writeFile("Android/song.mp3")
        writeFile("obb/song.mp3")
        writeFile("data/song.mp3")
        writeFile("cache/song.mp3")
        writeFile("tmp/song.mp3")
        writeFile("Music/song.mp3")

        val result = MediaScanner().scan(listOf(folder(workingDir)))

        // `data`, `cache` and `tmp` are ordinary folder names on a Windows library — Android skipped
        // them as system trees, and skipping them here would hide songs the user can see.
        assertEquals(
            listOf("cache", "data", "Music", "tmp"),
            result.songs.map { it.folderPath.substringAfterLast('\\') }.sortedBy { it.lowercase() },
        )
    }

    @Test
    fun `reports a root that does not exist instead of failing the scan`() {
        writeFile("Present/song.mp3")
        val present = folder(workingDir.resolve("Present").also { Files.createDirectories(it) })
        val absent = folder(workingDir.resolve("OnAnotherDrive"))

        val result = MediaScanner().scan(listOf(present, absent))

        assertEquals(setOf(present.id), result.successfulFolderIds)
        assertEquals(setOf(absent.id), result.missingFolderIds)
        assertTrue(result.failedFolderIds.isEmpty())
        assertEquals(listOf("song.mp3"), result.songs.map { it.songFile.fileName })
    }

    @Test
    fun `reports a root that cannot be listed as failed`() {
        val root = workingDir.resolve("Unreadable")
        val fake = FakeFileSystem(
            directories = mapOf(root to listOf(root.resolve("song.mp3"))),
            unreadable = setOf(root),
        )

        val rootFolder = folder(root)
        val result = MediaScanner(fake).scan(listOf(rootFolder))

        assertEquals(setOf(rootFolder.id), result.failedFolderIds)
        assertTrue(result.songs.isEmpty())
    }

    @Test
    fun `skips an unreadable subfolder but keeps scanning its siblings`() {
        val root = workingDir.resolve("Library")
        val blocked = root.resolve("Blocked")
        val open = root.resolve("Open")
        val fake = FakeFileSystem(
            directories = mapOf(
                root to listOf(blocked, open),
                open to listOf(open.resolve("kept.mp3")),
            ),
            unreadable = setOf(blocked),
        )

        val rootFolder = folder(root)
        val result = MediaScanner(fake).scan(listOf(rootFolder))

        assertEquals(setOf(rootFolder.id), result.successfulFolderIds, "an unreadable child is not a failed root")
        assertEquals(listOf("kept.mp3"), result.songs.map { it.songFile.fileName })
    }

    @Test
    fun `does not return the same file twice when a root is added twice`() {
        writeFile("Music/song.mp3")
        val music = workingDir.resolve("Music").also { Files.createDirectories(it) }

        val result = MediaScanner().scan(listOf(folder(music, id = 1L), folder(music, id = 2L)))

        assertEquals(1, result.songs.size, "one file is one row: songs.uri is UNIQUE")
        // The first root wins; the second pass finds nothing new.
        assertEquals(1L, result.songs.single().rootFolderId)
    }

    @Test
    fun `terminates when a junction points back at an ancestor`() {
        val root = workingDir.resolve("Library")
        Files.createDirectories(root.resolve("Album"))
        writeFile("Library/Album/song.mp3")
        val link = root.resolve("loop")

        // Creating a junction needs `cmd` and can be blocked by policy. A machine that cannot make
        // one cannot test this, which is a skip, not a pass and not a failure.
        assumeTrue(
            "could not create a junction to test directory cycle handling",
            createJunction(link, root),
        )

        val result = MediaScanner().scan(listOf(folder(root)))

        assertEquals(
            listOf("song.mp3"),
            result.songs.map { it.songFile.fileName },
            "a file reachable through the junction must not be scanned twice",
        )
    }

    @Test
    fun `falls back to the modification time when the filesystem has no creation time`() {
        val root = Path.of("C:\\fake\\Music")
        val song = root.resolve("song.mp3")
        val fake = FakeFileSystem(
            directories = mapOf(root to listOf(song)),
            attributes = mapOf(
                song to SongFileAttributes(
                    size = 10L,
                    lastModified = 1_700_000_000_000L,
                    created = 0L,
                    isDirectory = false,
                )
            ),
        )

        val result = MediaScanner(fake).scan(listOf(folder(root)))

        assertEquals(1_700_000_000_000L, result.songs.single().songFile.dateAdded)
    }

    @Test
    fun `uses the creation time as the date added when there is one`() {
        val root = Path.of("C:\\fake\\Music")
        val song = root.resolve("song.mp3")
        val fake = FakeFileSystem(
            directories = mapOf(root to listOf(song)),
            attributes = mapOf(
                song to SongFileAttributes(
                    size = 10L,
                    lastModified = 1_700_000_000_000L,
                    created = 1_600_000_000_000L,
                    isDirectory = false,
                )
            ),
        )

        val result = MediaScanner(fake).scan(listOf(folder(root)))

        assertEquals(1_600_000_000_000L, result.songs.single().songFile.dateAdded)
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun folder(path: Path, id: Long = 1L) = FolderEntity(id = id, path = path.toString(), addedBySaf = true)

    private fun writeFile(relativePath: String) {
        val target = workingDir.resolve(relativePath.replace('/', '\\'))
        Files.createDirectories(target.parent)
        Files.copy(FIXTURE, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Creates an NTFS junction at [link] pointing at [target]. Returns false when unavailable. */
    private fun createJunction(link: Path, target: Path): Boolean = try {
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        process.waitFor() == 0 && Files.exists(link)
    } catch (_: Exception) {
        false
    }

    private class FakeFileSystem(
        val directories: Map<Path, List<Path>> = emptyMap(),
        private val unreadable: Set<Path> = emptySet(),
        private val attributes: Map<Path, SongFileAttributes> = emptyMap(),
    ) : SongFileSystem {

        override fun isDirectory(path: Path): Boolean =
            path in directories || directories.keys.any { it.parent == path }

        override fun list(path: Path): List<Path> {
            if (path in unreadable) throw java.io.IOException("access denied: $path")
            return directories[path] ?: emptyList()
        }

        override fun attributes(path: Path): SongFileAttributes =
            attributes[path] ?: SongFileAttributes(
                size = directories[path]?.size?.toLong() ?: 0L,
                lastModified = 0L,
                created = 0L,
                isDirectory = isDirectory(path),
            )

        override fun realPath(path: Path): Path = path
    }

    private companion object {
        val FIXTURE: Path = Path.of(
            System.getProperty("lyrico.audiotag.fixtures.dir"),
            "bladeenc.mp3",
        )
    }
}
