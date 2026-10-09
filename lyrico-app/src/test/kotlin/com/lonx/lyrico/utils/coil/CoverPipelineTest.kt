package com.lonx.lyrico.utils.coil

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.lonx.audiotag.TagLib
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.audiotag.model.Picture
import com.lonx.lyrico.ui.components.CoverRequest
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cover pipeline end to end: a real audio file on disk, TagLib reading the embedded picture, the
 * app's own Coil fetcher and keyer, and Coil's decoder turning the bytes into an image.
 *
 * Covers are the one piece of the library UI that cannot degrade quietly — a broken pipeline shows
 * every song with a placeholder icon and no error anywhere — so the assertions are on decoded pixel
 * dimensions, not on "a request was made": each generated cover has a distinct, non-square size, and
 * the size that comes back is the size that was written.
 */
class CoverPipelineTest {

    private lateinit var workDir: Path

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("lyrico-covers-")
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    @Test
    fun `the embedded front cover is decoded to its real size`() = runBlocking<Unit> {
        val song = copyFixture("silence-44-s.flac")
        writeCover(song, width = 37, height = 23)
        val loader = loader()

        val result = loader.execute(request(song))

        val success = assertIs<SuccessResult>(result, "cover load failed: $result")
        val image = assertNotNull(success.image)
        assertEquals(37, image.width)
        assertEquals(23, image.height)
    }

    @Test
    fun `a file without artwork fails instead of rendering an empty image`() = runBlocking<Unit> {
        val song = copyFixture("bladeenc.mp3")

        val result = loader().execute(request(song))

        assertIs<ErrorResult>(result, "a song with no artwork must not resolve to an image: $result")
    }

    @Test
    fun `the artist poster folder supplies the cover when the file has none`() = runBlocking<Unit> {
        val song = copyFixture("bladeenc.mp3")
        val posterDir = workDir.resolve("posters").also { Files.createDirectories(it) }
        writePng(posterDir.resolve("Some Artist.png"), width = 41, height = 17)

        val result = loader().execute(
            request(song, artistName = "Some Artist", posterFolders = listOf(posterDir.toString()))
        )

        val success = assertIs<SuccessResult>(result, "poster folder lookup failed: $result")
        val image = assertNotNull(success.image)
        assertEquals(41, image.width)
        assertEquals(17, image.height)
    }

    @Test
    fun `an unreadable poster file does not shadow a working one in the same folder`() = runBlocking<Unit> {
        val song = copyFixture("bladeenc.mp3")
        val posterDir = workDir.resolve("posters").also { Files.createDirectories(it) }
        // Rank 0 (exact name) is the corrupt one, so a pipeline that trusts the first match returns
        // nothing at all and the artist looks like they have no poster.
        Files.write(posterDir.resolve("Some Artist.png"), "not an image".toByteArray())
        writePng(posterDir.resolve("Some Artist_b.png"), width = 41, height = 17)

        val result = loader().execute(
            request(song, artistName = "Some Artist", posterFolders = listOf(posterDir.toString()))
        )

        val success = assertIs<SuccessResult>(result, "the working poster was never tried: $result")
        assertEquals(41, assertNotNull(success.image).width)
    }

    @Test
    fun `a rewritten cover is re-decoded instead of served from the cache`() = runBlocking<Unit> {
        val song = copyFixture("silence-44-s.flac")
        val imageLoader = loader()
        writeCover(song, width = 37, height = 23)
        val first = assertIs<SuccessResult>(imageLoader.execute(request(song)))
        assertEquals(37, assertNotNull(first.image).width)
        writeCover(song, width = 61, height = 19)
        // The cache key is the path plus the file's modification time; a same-millisecond rewrite
        // would legitimately reuse the cached entry, so make the timestamp unambiguously newer.
        val bumped = Files.getLastModifiedTime(song).toMillis() + 5_000
        Files.setLastModifiedTime(song, FileTime.fromMillis(bumped))

        val second = assertIs<SuccessResult>(imageLoader.execute(request(song)))
        val updated = assertNotNull(second.image)

        assertEquals(61, updated.width, "the stale cover was served from the cache")
        assertEquals(19, updated.height)
    }

    @Test
    fun `the installed singleton loader decodes an embedded cover`() = runBlocking<Unit> {
        val song = copyFixture("silence-44-s.flac")
        writeCover(song, width = 37, height = 23)
        installImageLoader(cacheDir())

        val result = coil3.SingletonImageLoader.get(PlatformContext.INSTANCE).execute(request(song))

        assertIs<SuccessResult>(result, "the app's own image loader wiring failed: $result")
    }

    // ------------------------------------------------------------------ helpers

    private fun cacheDir(): File =
        workDir.resolve("cache").also { Files.createDirectories(it) }.toFile()

    private fun loader(): ImageLoader =
        buildImageLoader(
            context = PlatformContext.INSTANCE,
            cacheDir = cacheDir(),
        )

    private fun request(
        song: Path,
        artistName: String? = null,
        posterFolders: List<String> = emptyList(),
    ) = ImageRequest.Builder(PlatformContext.INSTANCE)
        .data(
            CoverRequest(
                uri = song.toString(),
                lastUpdate = Files.getLastModifiedTime(song).toMillis(),
                artistName = artistName,
                artistPosterFolders = posterFolders,
            )
        )
        .build()

    private fun writeCover(song: Path, width: Int, height: Int) {
        val png = pngBytes(width, height)
        assertTrue(
            TagLib.savePictures(
                song,
                arrayOf(Picture(png, "封面", AudioPictureType.FrontCover.tagLibName, "image/png")),
            ),
            "savePictures returned false",
        )
    }

    private fun writePng(target: Path, width: Int, height: Int) {
        Files.write(target, pngBytes(width, height))
    }

    private fun pngBytes(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { out ->
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out)
    }.toByteArray()

    private fun copyFixture(name: String): Path {
        val fixture = Path.of(System.getProperty(FIXTURES_DIR_PROPERTY), name)
        assertTrue(
            Files.isRegularFile(fixture),
            "missing audio fixture ${fixture.toAbsolutePath()} — set $FIXTURES_DIR_PROPERTY",
        )
        val target = workDir.resolve(name)
        Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    private companion object {
        const val FIXTURES_DIR_PROPERTY = "lyrico.audiotag.fixtures.dir"
    }
}
