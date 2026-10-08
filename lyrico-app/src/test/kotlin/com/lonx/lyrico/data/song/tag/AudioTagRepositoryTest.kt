package com.lonx.lyrico.data.song.tag

import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end tag read/write against real audio files, through the real chain the app uses:
 * mutation → resolver → `TagMapBuilder` → `lyrico-audiotag` (TagLib) → file → back through the reader.
 *
 * The fixtures are TagLib's own test files, already vendored under `lyrico-audiotag`, copied into a
 * temp directory so the repo files are never modified. An ID3v2 file (`.mp3`) and a Vorbis comment
 * file (`.flac`) are both covered, because the two containers write through different TagLib paths.
 *
 * This is the P3 acceptance test: tags actually land on disk and read back.
 */
class AudioTagRepositoryTest {

    private lateinit var workingDir: Path
    private lateinit var repository: AudioTagRepositoryImpl
    private lateinit var log: RecordingAppLogRepository

    @BeforeTest
    fun setUp() {
        workingDir = Files.createTempDirectory("lyrico-audio-tags")
        log = RecordingAppLogRepository()
        repository = AudioTagRepositoryImpl(
            fileAccess = AudioFileAccess(),
            mutationResolver = AudioTagMutationResolver(
                tagMapBuilder = TagMapBuilder(),
                pictureResolver = PictureMutationResolver(
                    imageBytesFetcher = DefaultImageBytesFetcher(AudioFileAccess(), OkHttpClient()),
                    mimeTypeDetector = ImageMimeTypeDetector(),
                ),
            ),
            appLogRepository = log,
        )
    }

    @AfterTest
    fun tearDown() {
        workingDir.toFile().deleteRecursively()
    }

    @Test
    fun `reads tags from an id3v2 file and a vorbis comment file`() = runBlocking<Unit> {
        for (fixture in FIXTURES) {
            val song = copyFixture(fixture)

            val tags = repository.read(song.toString(), AudioTagReadOptions())

            assertEquals(fixture, tags.fileName, "file name should come from the path, for $fixture")
            assertTrue(tags.durationMilliseconds > 0, "duration should be parsed for $fixture")
        }
    }

    @Test
    fun `overwrites tags on disk and reads them back`() = runBlocking<Unit> {
        for (fixture in FIXTURES) {
            val song = copyFixture(fixture)

            val result = repository.overwrite(
                song.toString(),
                mutation(
                    AudioTagFieldKey.Title to "Overwritten Title",
                    AudioTagFieldKey.Artist to "Overwritten Artist",
                    AudioTagFieldKey.Album to "Overwritten Album",
                )
            )

            val saved = assertIs<AudioTagWriteResult.Success>(result, "write should succeed for $fixture")
            assertEquals("Overwritten Title", saved.savedData.title, "returned snapshot, $fixture")

            // Read the file again from scratch: proves the values are on disk, not in the mutation.
            val fresh = repository.read(song.toString(), AudioTagReadOptions(strict = true))
            assertEquals("Overwritten Title", fresh.title, "title on disk, $fixture")
            assertEquals("Overwritten Artist", fresh.artist, "artist on disk, $fixture")
            assertEquals("Overwritten Album", fresh.album, "album on disk, $fixture")
        }
    }

    @Test
    fun `patch leaves the fields it does not mention alone`() = runBlocking<Unit> {
        val song = copyFixture(FIXTURES.first())
        repository.overwrite(
            song.toString(),
            mutation(
                AudioTagFieldKey.Title to "Kept Title",
                AudioTagFieldKey.Album to "Kept Album",
            )
        )

        repository.patch(
            song.toString(),
            mutation(AudioTagFieldKey.Title to "Patched Title"),
        )

        val fresh = repository.read(song.toString(), AudioTagReadOptions(strict = true))
        assertEquals("Patched Title", fresh.title)
        assertEquals("Kept Album", fresh.album, "a patched title must not drop the album")
    }

    @Test
    fun `writes a front cover and reads the bytes back`() = runBlocking<Unit> {
        val song = copyFixture(FIXTURES.first())
        val cover = onePixelPng()

        val result = repository.overwrite(
            song.toString(),
            AudioTagMutation(
                mode = AudioTagMutationMode.Overwrite,
                fields = mapOf(AudioTagFieldKey.Title to FieldMutation.Set("With Cover")),
                pictureUpdate = PictureUpdate.ReplaceFrontCover(PictureSource.Bytes(cover)),
            )
        )
        assertIs<AudioTagWriteResult.Success>(result)

        val fresh = repository.read(song.toString(), AudioTagReadOptions(strict = true))
        val picture = fresh.pictures.singleOrNull() ?: fail("cover should be readable: ${fresh.pictures}")
        assertTrue(
            picture.mimeType.contains("png", ignoreCase = true),
            "mime type should be detected from the bytes, was ${picture.mimeType}",
        )
        assertTrue(cover.contentEquals(picture.data), "cover bytes should round-trip unchanged")
    }

    @Test
    fun `lenient read degrades on a missing file, strict read reports it`() = runBlocking<Unit> {
        val missing = workingDir.resolve("missing.mp3")

        val lenient = repository.read(missing.toString(), AudioTagReadOptions())
        assertEquals("missing.mp3", lenient.fileName)
        assertEquals(null, lenient.title)

        assertFailsWith<Exception> {
            repository.read(missing.toString(), AudioTagReadOptions(strict = true))
        }
    }

    @Test
    fun `a leftover content uri degrades instead of breaking the read`() = runBlocking<Unit> {
        // A library database copied over from Android can still hold `content://` rows; one of them
        // must not be able to break a read (and a scan reads every row).
        val storedValue = "content://com.android.providers.media.documents/document/audio%3A42"

        val lenient = repository.read(storedValue, AudioTagReadOptions())
        assertEquals(null, lenient.title)

        assertFailsWith<Exception> { repository.read(storedValue, AudioTagReadOptions(strict = true)) }

        val writeResult = repository.patch(
            storedValue,
            mutation(AudioTagFieldKey.Title to "nope"),
        )
        assertIs<AudioTagWriteResult.Failed>(writeResult)
    }

    @Test
    fun `a failed write is reported as a failure and logged`() = runBlocking<Unit> {
        val result = repository.overwrite(
            workingDir.resolve("does-not-exist.flac").toString(),
            mutation(AudioTagFieldKey.Title to "x"),
        )

        assertIs<AudioTagWriteResult.Failed>(result)
        assertTrue(
            log.exceptions.any { it.type == AppLogType.METADATA },
            "the failure should be recorded in the app log: ${log.exceptions}",
        )
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun mutation(vararg fields: Pair<AudioTagFieldKey, String>) = AudioTagMutation(
        mode = AudioTagMutationMode.Patch,
        fields = fields.associate { (key, value) -> key to FieldMutation.Set(value) },
    )

    private fun copyFixture(name: String): Path {
        val fixture = Path.of(System.getProperty(FIXTURES_DIR_PROPERTY), name)
        assertTrue(
            Files.isRegularFile(fixture),
            "missing audio fixture ${fixture.toAbsolutePath()} — set $FIXTURES_DIR_PROPERTY",
        )
        val target = workingDir.resolve(name)
        Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    private fun onePixelPng(): ByteArray = ByteArrayOutputStream().also { out ->
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", out)
    }.toByteArray()

    private companion object {
        const val FIXTURES_DIR_PROPERTY = "lyrico.audiotag.fixtures.dir"

        /**
         * TagLib's own test files, vendored in the audiotag module: ID3v2 (`bladeenc.mp3`) and Vorbis
         * comments (`silence-44-s.flac`). Both are real audio, not the degenerate stubs some of the
         * fixtures are — a stub cannot carry a duration or survive a tag write. The same two files
         * are the ones the native smoke harness verifies against (`scripts/native-smoke.ps1`).
         */
        val FIXTURES = listOf("bladeenc.mp3", "silence-44-s.flac")
    }
}
