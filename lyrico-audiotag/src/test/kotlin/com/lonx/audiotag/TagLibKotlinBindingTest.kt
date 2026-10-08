package com.lonx.audiotag

import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.audiotag.model.Picture
import com.lonx.audiotag.model.type
import com.lonx.audiotag.rw.AudioTagReader
import com.lonx.audiotag.rw.AudioTagWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * Exercises the Kotlin facade ([TagLib]) - not the raw JNI surface, which `tools/native-smoke`
 * already covers.
 *
 * What is specific to this layer, and therefore worth guarding: the `@JvmStatic @JvmOverloads`
 * default-argument overloads the app actually calls, the `Path`-based ABI, the picture selection
 * rules the artist-artwork feature depends on (type **and** description), and the
 * [AudioTagReader] / [AudioTagWriter] mapping built on top of it.
 *
 * The tests are skipped (not failed) when the native DLLs have not been built yet, so a fresh
 * checkout can run `./gradlew :lyrico-audiotag:test` before `scripts/build-native.ps1` has run.
 * To actually exercise them, build the DLLs first: `pwsh -File scripts/build-native.ps1`.
 */
class TagLibKotlinBindingTest {

    private lateinit var workDir: Path

    @Before
    fun setUp() {
        try {
            TagLib.ensureLoaded()
        } catch (e: UnsatisfiedLinkError) {
            Assume.assumeNoException(
                "native taglib DLL is not built yet - run scripts/build-native.ps1",
                e
            )
        }
        workDir = Files.createTempDirectory("lyrico-taglib-binding-")
    }

    // ------------------------------------------------------------------ helpers

    private fun fixturesDir(): Path {
        val configured = System.getProperty("lyrico.tests.fixtures")
        val dir = if (configured != null) {
            Path.of(configured)
        } else {
            Path.of("src", "main", "cpp", "taglib", "tests", "data")
        }
        Assume.assumeTrue("TagLib fixtures not found at $dir", Files.isDirectory(dir))
        return dir
    }

    /** Copies a TagLib fixture into the per-test temp dir so writes never touch the repository. */
    private fun fixture(name: String): Path {
        val source = fixturesDir().resolve(name)
        Assume.assumeTrue("fixture $name is missing", Files.isRegularFile(source))
        val target = workDir.resolve(name)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    private fun copyTo(file: Path, relativeName: String): Path {
        val target = workDir.resolve(relativeName)
        Files.createDirectories(target.parent)
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /** Raw `pictureType` strings, for failure messages: TagLib's spelling per container. */
    private fun rawTypes(pictures: Array<Picture>): String =
        pictures.joinToString(prefix = "[", postfix = "]") { "'${it.pictureType}'" }

    private fun Array<Picture>.ofType(type: AudioPictureType): List<Picture> =
        filter { AudioPictureType.fromTagLibName(it.pictureType) == type }

    private fun List<AudioPicture>.ofType(type: AudioPictureType): List<AudioPicture> =
        filter { it.type == type }

    // ------------------------------------------------------------------ audio properties

    @Test
    fun `reads audio properties through the Kotlin facade`() {
        val file = fixture("silence-44-s.flac")

        val props = TagLib.getAudioProperties(file)

        assertNotNull("getAudioProperties returned null", props)
        props!!
        assertEquals("sampleRate", 44100, props.sampleRate)
        assertEquals("channels", 2, props.channels)
        assertTrue("length should be > 0, got ${props.length}", props.length > 0)
        assertTrue("bitrate should be > 0, got ${props.bitrate}", props.bitrate > 0)
    }

    @Test
    fun `missing file yields empty properties instead of throwing`() {
        val missing = workDir.resolve("definitely-missing-12345.flac")

        val props = TagLib.getAudioProperties(missing)

        assertNotNull("getAudioProperties must not return null for a missing file", props)
        assertEquals("sampleRate", 0, props!!.sampleRate)
        assertEquals("length", 0, props.length)
        assertNull("getMetadata of a missing file", TagLib.getMetadata(missing, false))
        assertEquals("getPictures of a missing file", 0, TagLib.getPictures(missing).size)
    }

    // ------------------------------------------------------------------ metadata / overloads

    @Test
    fun `default argument overload matches the explicit one`() {
        val file = fixture("silence-44-s.flac")

        // The no-arg call goes through the generated @JvmOverloads bridge - the form the app uses.
        val defaulted = TagLib.getMetadata(file)
        val explicit = TagLib.getMetadata(file, readPictures = true)

        assertNotNull("getMetadata(file) returned null", defaulted)
        assertNotNull("getMetadata(file, true) returned null", explicit)
        assertEquals(
            "propertyMap keys differ between the default and the explicit overload",
            explicit!!.propertyMap.keys,
            defaulted!!.propertyMap.keys
        )
        assertEquals(
            "picture count differs between the default and the explicit overload",
            explicit.pictures.size,
            defaulted.pictures.size
        )
    }

    @Test
    fun `readPictures false skips pictures but keeps properties`() {
        val file = fixture("silence-44-s.flac")
        assertTrue(TagLib.savePropertyMap(file, hashMapOf("TITLE" to arrayOf("with picture"))))
        assertTrue(
            TagLib.savePictures(
                file,
                arrayOf(Picture(PNG_A, "", AudioPictureType.FrontCover.tagLibName, "image/png"))
            )
        )

        val withPictures = TagLib.getMetadata(file, readPictures = true)!!
        val withoutPictures = TagLib.getMetadata(file, readPictures = false)!!

        assertEquals("readPictures=true should see the cover", 1, withPictures.pictures.size)
        assertTrue(
            "readPictures=false must still return the property map",
            withoutPictures.propertyMap.containsKey("TITLE")
        )
        assertTrue(
            "readPictures=false must not return pictures, got ${withoutPictures.pictures.size}",
            withoutPictures.pictures.isEmpty()
        )
    }

    @Test
    fun `property lookup returns the value and null for an absent property`() {
        val file = fixture("silence-44-s.flac")
        assertTrue(TagLib.savePropertyMap(file, hashMapOf("TITLE" to arrayOf("lookup me"))))

        val present = TagLib.getMetadataPropertyValues(file, "TITLE")
        val absent = TagLib.getMetadataPropertyValues(file, "LYRICO_DEFINITELY_ABSENT")

        assertEquals("TITLE value count", 1, present?.size)
        assertEquals("TITLE value", "lookup me", present?.firstOrNull())
        assertNull("an absent property must come back as null", absent)
    }

    // ------------------------------------------------------------------ tag write round-trip

    @Test
    fun `property map round trip keeps unicode values exactly`() {
        val file = fixture("silence-44-s.flac")
        val title = "Lyrico 桌面端 ✓ — テスト"
        val artist = "Lonx"
        val lyrics = "第一行\n第二行\nline three"

        val saved = TagLib.savePropertyMap(
            file,
            hashMapOf(
                "TITLE" to arrayOf(title),
                "ARTIST" to arrayOf(artist),
                "ALBUM" to arrayOf("Windows Port"),
                "LYRICS" to arrayOf(lyrics)
            )
        )
        assertTrue("savePropertyMap returned false", saved)

        val metadata = TagLib.getMetadata(file, readPictures = false)
        assertNotNull("getMetadata after save returned null", metadata)
        assertEquals("TITLE round-trip", title, metadata!!.propertyMap["TITLE"]?.firstOrNull())
        assertEquals("ARTIST round-trip", artist, metadata.propertyMap["ARTIST"]?.firstOrNull())
        assertEquals("ALBUM round-trip", "Windows Port", metadata.propertyMap["ALBUM"]?.firstOrNull())
        assertEquals("LYRICS round-trip", lyrics, metadata.propertyMap["LYRICS"]?.firstOrNull())

        // The file must remain a valid audio file: a tag write does not touch the audio stream.
        val props = TagLib.getAudioProperties(file)!!
        assertEquals("sampleRate after write", 44100, props.sampleRate)
        assertTrue("length after write should be > 0", props.length > 0)
    }

    @Test
    fun `writer and reader round trip the app level model`() = runBlocking {
        val file = fixture("silence-44-s.flac")

        val written = AudioTagWriter.writeTags(
            path = file,
            updates = mapOf(
                "TITLE" to "标题 Title",
                "ARTIST" to "Artist A",
                "ALBUM" to "Album",
                "TRACKNUMBER" to "7",
                "CUSTOMDESKTOPFIELD" to "kept verbatim"
            ),
            preserveOldTags = true
        )
        assertTrue("writeTags returned false", written)

        val data = AudioTagReader.read(file)

        assertEquals("title", "标题 Title", data.title)
        assertEquals("artist", "Artist A", data.artist)
        assertEquals("album", "Album", data.album)
        assertEquals("trackNumber", "7", data.trackNumber)
        assertEquals("sampleRate", 44100, data.sampleRate)
        assertTrue(
            "duration should be > 0, got ${data.durationMilliseconds}",
            data.durationMilliseconds > 0
        )
        assertTrue(
            "unknown keys must surface as custom fields, got ${data.customFields.map { it.key }}",
            data.customFields.any { it.key.equals("CUSTOMDESKTOPFIELD", ignoreCase = true) }
        )
        assertTrue(
            "reserved keys must not surface as custom fields",
            data.customFields.none { it.key.equals("TITLE", ignoreCase = true) }
        )
    }

    // ------------------------------------------------------------------ pictures

    @Test
    fun `pictures round trip with their type and description`() {
        val file = fixture("silence-44-s.flac")
        val front = Picture(TINY_PNG, "封面", AudioPictureType.FrontCover.tagLibName, "image/png")
        val artistA = Picture(PNG_A, "艺术家 A", AudioPictureType.Artist.tagLibName, "image/png")
        val artistB = Picture(PNG_B, "艺术家 B", AudioPictureType.Artist.tagLibName, "image/png")
        val unnamed = Picture(PNG_C, "", AudioPictureType.Artist.tagLibName, "image/png")

        assertTrue(
            "savePictures returned false",
            TagLib.savePictures(file, arrayOf(front, artistA, artistB, unnamed))
        )

        val pictures = TagLib.getPictures(file)
        assertEquals("picture count, raw types=${rawTypes(pictures)}", 4, pictures.size)

        val cover = pictures.ofType(AudioPictureType.FrontCover).firstOrNull()
        assertNotNull("front cover not found, raw types=${rawTypes(pictures)}", cover)
        assertTrue("front cover bytes", TINY_PNG.contentEquals(cover!!.data))
        assertEquals("front cover description", "封面", cover.description)
        assertEquals("front cover mime", "image/png", cover.mimeType)

        // The description is what tells two pictures of the same type apart - the artist artwork
        // feature relies on exactly this.
        val requested = TagLib.getPicture(file, AudioPictureType.Artist, description = "艺术家 B")
        assertNotNull("artist picture '艺术家 B' not found, raw types=${rawTypes(pictures)}", requested)
        assertTrue("wrong bytes for '艺术家 B'", PNG_B.contentEquals(requested!!.data))

        val caseInsensitive =
            TagLib.getPicture(file, AudioPictureType.Artist, description = "  艺术家 b  ")
        assertNotNull("description match must ignore case and surrounding space", caseInsensitive)
        assertTrue(PNG_B.contentEquals(caseInsensitive!!.data))

        val byArtist = TagLib.getArtistPicture(file, "艺术家 A")
        assertNotNull("getArtistPicture('艺术家 A') returned null", byArtist)
        assertTrue(PNG_A.contentEquals(byArtist!!.data))

        // An unmatched artist falls back to a picture that carries no description - those were
        // written before descriptions were used, so they cannot contradict the request.
        val noDescription = TagLib.getArtistPicture(file, "nobody")
        assertNotNull("a description-less artist picture should be used as a fallback", noDescription)
        assertTrue("wrong fallback bytes", PNG_C.contentEquals(noDescription!!.data))
    }

    @Test
    fun `another artists picture is only returned when explicitly allowed`() {
        // Deliberately no description-less picture here, so the fallback chain has to stop.
        val file = fixture("xing.mp3")
        val artistA = Picture(PNG_A, "艺术家 A", AudioPictureType.Artist.tagLibName, "image/png")
        val artistB = Picture(PNG_B, "艺术家 B", AudioPictureType.Artist.tagLibName, "image/png")
        assertTrue(TagLib.savePictures(file, arrayOf(artistA, artistB)))

        val pictures = TagLib.getPictures(file)
        assertEquals("picture count, raw types=${rawTypes(pictures)}", 2, pictures.size)

        assertNull(
            "no match and no description-less picture must not guess",
            TagLib.getPicture(file, AudioPictureType.Artist, description = "nobody")
        )
        assertNull(
            "getArtistPicture must not guess either",
            TagLib.getArtistPicture(file, "nobody")
        )
        assertNull(
            "a type nothing was saved as must not match",
            TagLib.getPicture(file, AudioPictureType.BackCover, description = "nobody")
        )

        val relaxed = TagLib.getPicture(
            file,
            AudioPictureType.Artist,
            fallbackToAny = true,
            description = "nobody"
        )
        assertNotNull("fallbackToAny=true should return another artist's picture", relaxed)
    }

    @Test
    fun `front cover lookup falls back to any picture`() {
        val file = fixture("silence-44-s.flac")
        val bandLogo = Picture(PNG_A, "", AudioPictureType.BandLogo.tagLibName, "image/png")
        assertTrue(TagLib.savePictures(file, arrayOf(bandLogo)))

        val cover = TagLib.getFrontCover(file)

        assertNotNull("getFrontCover must fall back to any available picture", cover)
        assertTrue("fallback picture bytes", PNG_A.contentEquals(cover!!.data))
    }

    @Test
    fun `pictures survive the app level writer and reader`() = runBlocking {
        val file = fixture("silence-44-s.flac")
        val cover = AudioPicture(
            data = TINY_PNG,
            mimeType = "image/png",
            description = "封面",
            pictureType = AudioPictureType.FrontCover.tagLibName
        )
        val artist = AudioPicture(
            data = PNG_B,
            mimeType = "image/png",
            description = "艺术家 B",
            pictureType = AudioPictureType.Artist.tagLibName
        )

        assertTrue("writePictures returned false", AudioTagWriter.writePictures(file, listOf(cover, artist)))

        val data = AudioTagReader.read(file)
        assertEquals("picture count", 2, data.pictures.size)
        assertEquals(
            "front cover type as the app sees it",
            AudioPictureType.FrontCover,
            data.pictures.ofType(AudioPictureType.FrontCover).firstOrNull()?.type
        )
        assertEquals(
            "artist picture type as the app sees it",
            AudioPictureType.Artist,
            data.pictures.ofType(AudioPictureType.Artist).firstOrNull()?.type
        )
        assertEquals(
            "artist picture description",
            "艺术家 B",
            data.pictures.ofType(AudioPictureType.Artist).firstOrNull()?.description
        )
    }

    // ------------------------------------------------------------------ paths

    @Test
    fun `non ascii and unicode paths work end to end`() = runBlocking {
        val source = fixture("silence-44-s.flac")
        val target = copyTo(source, "专辑 目录/歌曲 - テスト ✓.flac")

        assertTrue(
            "write through a non-ASCII path failed",
            TagLib.savePropertyMap(target, hashMapOf("TITLE" to arrayOf("Unicode 路径")))
        )

        val data = AudioTagReader.read(target)

        assertEquals("title read back through a non-ASCII path", "Unicode 路径", data.title)
        assertEquals("sampleRate through a non-ASCII path", 44100, data.sampleRate)
    }

    @Test
    fun `reader tolerates a missing file and reports an empty model`() = runBlocking {
        val missing = workDir.resolve("nope").resolve("nothing-here.mp3")

        val data = AudioTagReader.read(missing)

        assertNull("title of a missing file", data.title)
        assertEquals("sampleRate of a missing file", 0, data.sampleRate)
        assertTrue("pictures of a missing file", data.pictures.isEmpty())
    }

    private companion object {
        /** 1x1 PNG - same bytes `tools/native-smoke` uses. */
        private val TINY_PNG = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
            'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A,
            'I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(),
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00,
            0x00, 0x00,
            'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(),
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )

        /** The same PNG with three different trailers, so the pictures can be told apart. */
        private val PNG_A = TINY_PNG.copyOf().also { it[it.lastIndex] = 0x83.toByte() }
        private val PNG_B = TINY_PNG.copyOf().also { it[it.lastIndex - 1] = 0x61.toByte() }
        private val PNG_C = TINY_PNG.copyOf().also { it[it.lastIndex - 2] = 0x37.toByte() }
    }
}
