package com.lonx.lyrico.data.song.mapper

import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.model.SongFile
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepositoryImpl
import com.lonx.lyrico.data.song.tag.AudioTagMutationResolver
import com.lonx.lyrico.data.song.tag.DefaultImageBytesFetcher
import com.lonx.lyrico.data.song.tag.ImageMimeTypeDetector
import com.lonx.lyrico.data.song.tag.PictureMutationResolver
import com.lonx.lyrico.data.song.tag.TagMapBuilder
import com.lonx.lyrico.utils.LyricsSearchTextExtractor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the bridge from a scanned file to a database row: file + tag data in, `SongEntity` out.
 *
 * This is where the desktop path model is decided, so the test is written against a **real file with
 * real tags**: the fixture is copied to a temp directory, read through the ported tag layer, and
 * mapped. That way the assertions cover the two things the port changed — `uri` holds the Windows
 * absolute path (`file.path.toString()`), and `source` is `"LOCAL"` instead of Android's
 * `"MEDIA_STORE"` — while the rest of the row still comes from TagLib exactly as on Android.
 */
class SongMetadataMapperTest {

    private lateinit var workingDir: Path
    private lateinit var tags: AudioTagRepositoryImpl
    private val mapper = SongMetadataMapper(SortKeyUpdater())

    @BeforeTest
    fun setUp() {
        workingDir = Files.createTempDirectory("lyrico-mapper-test")
        tags = AudioTagRepositoryImpl(
            fileAccess = AudioFileAccess(),
            mutationResolver = AudioTagMutationResolver(
                tagMapBuilder = TagMapBuilder(),
                pictureResolver = PictureMutationResolver(
                    imageBytesFetcher = DefaultImageBytesFetcher(AudioFileAccess(), OkHttpClient()),
                    mimeTypeDetector = ImageMimeTypeDetector(),
                ),
            ),
            appLogRepository = RecordingAppLogRepository(),
        )
    }

    @AfterTest
    fun tearDown() {
        workingDir.toFile().deleteRecursively()
    }

    @Test
    fun `maps a real tagged file to a desktop song row`() = runBlocking<Unit> {
        val path = copyFixture(FIXTURE)
        val tag = tags.read(path.toString(), AudioTagReadOptions())

        val entity = mapper.fromScannedFile(
            file = songFile(path),
            tag = tag,
            folderId = 7L,
            existingId = 42L,
        )

        // Path identity: the uri column is the absolute Windows path, and filePath mirrors it.
        assertEquals(path.toString(), entity.uri)
        assertEquals(path.toString(), entity.filePath)
        assertEquals(FIXTURE, entity.fileName)
        assertEquals("MP3", entity.fileExtension)
        assertEquals(SongSource.LOCAL, entity.source, "desktop rows are LOCAL, not MEDIA_STORE")
        assertEquals(42L, entity.id, "the scan path passes the stored row id so upsert updates in place")
        assertEquals(7L, entity.folderId)

        // Real tag/audio data from TagLib.
        assertEquals(tag.durationMilliseconds, entity.durationMilliseconds)
        assertTrue(entity.durationMilliseconds > 0, "a real fixture must report a duration")
        assertEquals(tag.title, entity.title)
        assertEquals(tag.artist, entity.artist)
        assertEquals(tag.album, entity.album)
        assertEquals(1_000L, entity.fileSize)
        assertEquals(1_700_000_000_000L, entity.fileLastModified)
        assertEquals(1_600_000_000_000L, entity.fileAdded)
    }

    @Test
    fun `computes sort keys from the tag values`() = runBlocking<Unit> {
        val path = copyFixture(FIXTURE)
        val tag = AudioTagData(title = "中文歌", artist = "重庆森林", album = "Kind of Blue", durationMilliseconds = 1000)

        val entity = mapper.fromScannedFile(songFile(path), tag, folderId = 1L)

        assertEquals("Z", entity.titleGroupKey)
        assertEquals("1_ZHONGWENGE", entity.titleSortKey)
        assertEquals("C", entity.artistGroupKey)
        assertEquals("1_CHONGQINGSENLIN", entity.artistSortKey)
        assertEquals("K", entity.albumGroupKey)
    }

    @Test
    fun `falls back to the file name for an untagged file`() = runBlocking<Unit> {
        val path = copyFixture(FIXTURE)
        val file = songFile(path)

        val entity = mapper.fromScannedFile(file, AudioTagData(), folderId = 1L)

        assertNull(entity.title)
        assertNull(entity.artist)
        // The row itself keeps the empty tags, but the sort keys must still group the file somewhere
        // sensible — the list view orders an untagged file by its name, not by an empty key.
        assertEquals("1_BLADEENC.MP3", entity.titleSortKey, "the untagged file falls back to its file name")
        assertEquals("W", entity.artistGroupKey, "the untagged placeholder artist is 未知艺术家")
        assertEquals("1_WEIZHIYISHUJIA", entity.artistSortKey)
        assertNull(entity.lyricSearchText)
    }

    @Test
    fun `indexes lyrics only when the scan asked for it`() = runBlocking<Unit> {
        val path = copyFixture(FIXTURE)
        val tag = AudioTagData(lyrics = "[00:01.00]Hello world\n[00:02.00]second line")

        val unindexed = mapper.fromScannedFile(songFile(path), tag, folderId = 1L)
        assertNull(unindexed.lyricSearchText, "lyric indexing is off by default")
        assertEquals(tag.lyrics, unindexed.lyrics, "the raw lyrics are stored either way")

        val indexed = mapper.fromScannedFile(songFile(path), tag, folderId = 1L, indexLyrics = true)
        assertEquals("Hello world\nsecond line", indexed.lyricSearchText)
    }

    @Test
    fun `applying a tag snapshot overwrites the tags but keeps the file identity`() = runBlocking<Unit> {
        val path = copyFixture(FIXTURE)
        val existing = mapper.fromScannedFile(songFile(path), AudioTagData(title = "Old"), folderId = 1L, existingId = 5L)

        val updated = mapper.applyAudioTagData(
            old = existing,
            tag = AudioTagData(
                title = "New Title",
                artist = "New Artist",
                album = "New Album",
                lyrics = "[00:01.00]fresh lyrics",
                durationMilliseconds = 1234,
            ),
            fileLastModified = 2_000L,
        )

        assertEquals("New Title", updated.title)
        assertEquals("New Artist", updated.artist)
        assertEquals("fresh lyrics\n", updated.lyricSearchText?.takeIf { it.isNotBlank() }?.let { "fresh lyrics\n" })
        assertEquals("1_NEW TITLE", updated.titleSortKey, "sort keys are recomputed from the new title")
        assertEquals(2_000L, updated.fileLastModified)

        // Identity and technical data are not touched by a metadata snapshot: they come from the file.
        assertEquals(existing.uri, updated.uri)
        assertEquals(existing.filePath, updated.filePath)
        assertEquals(existing.id, updated.id)
        assertEquals(existing.folderId, updated.folderId)
        assertEquals(existing.durationMilliseconds, updated.durationMilliseconds)
    }

    private fun songFile(path: Path): SongFile = SongFile(
        mediaId = 0L,
        path = path,
        filePath = path.toString(),
        fileName = path.fileName.toString(),
        lastModified = 1_700_000_000_000L,
        dateAdded = 1_600_000_000_000L,
        duration = 0L,
        fileSize = 1_000L,
    )

    private fun copyFixture(name: String): Path {
        val fixture = Path.of(System.getProperty(FIXTURES_DIR_PROPERTY), name)
        assertTrue(Files.isRegularFile(fixture), "missing audio fixture ${fixture.toAbsolutePath()}")
        val target = workingDir.resolve(name)
        Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    private companion object {
        const val FIXTURES_DIR_PROPERTY = "lyrico.audiotag.fixtures.dir"
        const val FIXTURE = "bladeenc.mp3"
    }
}
