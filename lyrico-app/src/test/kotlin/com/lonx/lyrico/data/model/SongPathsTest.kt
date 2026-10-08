package com.lonx.lyrico.data.model

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the Windows path rules that back the `songs.uri` `UNIQUE` column.
 *
 * The properties here are what make library identity work: one file must have exactly one stored
 * spelling (so a rescan does not duplicate it), stale paths must stay readable, and inherited
 * `content://` values must be rejected rather than turned into nonsense paths.
 */
class SongPathsTest {

    private val workingDir: Path = Files.createTempDirectory("lyrico-songpaths-test")

    @AfterTest
    fun tearDown() {
        workingDir.toFile().deleteRecursively()
    }

    @Test
    fun `rejects values that are not windows paths`() {
        assertNull(SongPaths.canonicalize(null), "null is not a path")
        assertNull(SongPaths.canonicalize("   "), "blank is not a path")
        assertNull(
            SongPaths.canonicalize("content://media/external/audio/media/42"),
            "an inherited Android uri must not become a path",
        )
        assertNull(SongPaths.canonicalize("Music\\album\\a.mp3"), "relative paths are not library identity")
        assertNull(SongPaths.canonicalize("a.mp3"), "bare file names are not library identity")
    }

    @Test
    fun `resolves the real spelling of an existing file`() {
        val file = Files.createFile(workingDir.resolve("Track One.flac"))
        val real = file.toRealPath()

        // A sloppy spelling: upper-cased drive, folders and file name, plus a `..` segment that
        // cancels the folder it sits in. Explorer opens this file; the library must see one song.
        val sloppy = real.parent.toString().uppercase() +
            "\\..\\" + real.parent.fileName.toString() + "\\" + real.fileName.toString().uppercase()

        val canonical = assertNotNull(SongPaths.canonicalize(sloppy), "sloppy spelling should canonicalise")
        assertEquals(real, canonical, "sloppy spelling should canonicalise to the real file")
        assertTrue(Files.exists(canonical), "the canonical path must still open the file")
        assertEquals("Track One.flac", canonical.fileName.toString(), "stored spelling keeps real casing")
    }

    @Test
    fun `keeps missing paths usable instead of throwing`() {
        val missing = workingDir.resolve("not-there").resolve("..").resolve("also-missing.opus")

        val canonical = SongPaths.canonicalize(missing)

        assertEquals(workingDir.resolve("also-missing.opus"), canonical)
        assertTrue(canonical.isAbsolute, "a fallback path is still absolute so it can be stored")
    }

    @Test
    fun `strips the extended length prefix`() {
        val file = Files.createFile(workingDir.resolve("Prefixed.mp3"))
        val real = file.toRealPath()

        assertEquals(real, SongPaths.canonicalize("\\\\?\\$real"), "\\\\?\\ paths must be stored without the prefix")
        assertEquals(real, SongPaths.canonicalize(Path.of("\\\\?\\$real")))
    }

    @Test
    fun `identity keys compare case insensitively`() {
        val a = workingDir.resolve("CASE\\Mixed.mp3")
        val b = workingDir.resolve("case\\mixed.MP3")

        assertEquals(SongPaths.identityKey(a), SongPaths.identityKey(b))
        assertEquals(SongPaths.identityKey(a), SongPaths.identityKey(b.toString()))
        assertNull(SongPaths.identityKey("content://media/external/audio/media/42"), "no identity for a non-path")
    }

    @Test
    fun `canonicalizing twice is stable`() {
        val file = Files.createFile(workingDir.resolve("Stable.ogg"))
        val once = SongPaths.canonicalize(file)
        assertEquals(once, SongPaths.canonicalize(once), "canonicalisation must be idempotent")
        assertEquals(SongPaths.identityKey(once), SongPaths.identityKey(SongPaths.canonicalize(once)))
    }
}
