package com.lonx.lyrico.data.repository

import com.lonx.audiotag.model.CustomTagField
import com.lonx.lyrico.data.support.TestLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The custom tag key index, against a real database.
 *
 * The index exists so the library can be filtered by a tag key the user invented, which means the
 * keys have to be comparable: `CustomTagKey.normalize` decides what a key is (trimmed, upper-cased,
 * length-limited, no newlines) and this repository is the only place that applies it before writing.
 * A key written unnormalized would still be found by an exact-match query and silently missing from
 * every other one, so the tests assert the *stored* form, not just the round trip.
 */
class CustomTagKeyRepositoryTest {

    private lateinit var library: TestLibrary
    private lateinit var repository: CustomTagKeyRepository

    private val dao get() = library.database.songCustomTagKeyDao()

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        repository = CustomTagKeyRepository(dao = dao)
    }

    @AfterTest
    fun tearDown() {
        library.close()
    }

    @Test
    fun `the fields of a song are recorded under normalized keys`() = runBlocking<Unit> {
        repository.replaceForSong(SONG_A, listOf(CustomTagField(" mood ", "calm"), CustomTagField("Scene", "night")))

        assertEquals(listOf("MOOD", "SCENE"), dao.getKeysForSong(SONG_A))
    }

    @Test
    fun `a key is found however the caller spells it`() = runBlocking<Unit> {
        repository.replaceForSong(SONG_A, listOf(CustomTagField("MOOD", "calm")))

        assertEquals(listOf(SONG_A), repository.getSongUrisByKey("  mood  "))
        assertEquals(listOf(SONG_A), repository.getSongUrisByKey("MoOd"))
    }

    @Test
    fun `a key with nothing in it is not recorded`() = runBlocking<Unit> {
        repository.replaceForSong(
            SONG_A,
            listOf(
                CustomTagField("", "no key"),
                CustomTagField("   ", "blank key"),
                CustomTagField("real", "kept"),
            ),
        )

        assertEquals(listOf("REAL"), dao.getKeysForSong(SONG_A))
    }

    @Test
    fun `a key longer than the limit is not recorded`() = runBlocking<Unit> {
        repository.replaceForSong(
            SONG_A,
            listOf(
                CustomTagField("k".repeat(64), "at the limit"),
                CustomTagField("k".repeat(65), "over the limit"),
            ),
        )

        assertEquals(listOf("K".repeat(64)), dao.getKeysForSong(SONG_A))
    }

    @Test
    fun `a key that carries a line break is not recorded`() = runBlocking<Unit> {
        // The key ends up in SQL and in a filter chip; a newline in it breaks both.
        repository.replaceForSong(SONG_A, listOf(CustomTagField("bad\nkey", "x"), CustomTagField("good", "x")))

        assertEquals(listOf("GOOD"), dao.getKeysForSong(SONG_A))
    }

    @Test
    fun `replacing the fields of a song drops the keys it no longer has`() = runBlocking<Unit> {
        repository.replaceForSong(SONG_A, listOf(CustomTagField("MOOD", "calm"), CustomTagField("SCENE", "night")))

        repository.replaceForSong(SONG_A, listOf(CustomTagField("SCENE", "day")))

        assertEquals(listOf("SCENE"), dao.getKeysForSong(SONG_A))
        assertTrue(repository.getSongUrisByKey("MOOD").isEmpty())
    }

    @Test
    fun `repeating a key in one field list stores it once`() = runBlocking<Unit> {
        // Two fields that normalize to the same key would violate the (songUri, key) primary key.
        repository.replaceForSong(SONG_A, listOf(CustomTagField("mood", "a"), CustomTagField("MOOD", "b")))

        assertEquals(listOf("MOOD"), dao.getKeysForSong(SONG_A))
    }

    @Test
    fun `a key shared by two songs counts both`() = runBlocking<Unit> {
        repository.replaceForSong(SONG_A, listOf(CustomTagField("MOOD", "calm")))
        repository.replaceForSong(SONG_B, listOf(CustomTagField("MOOD", "loud"), CustomTagField("SCENE", "night")))

        val counts = repository.observeKeyCounts().first().associate { it.key to it.songCount }

        assertEquals(mapOf("MOOD" to 2, "SCENE" to 1), counts)
        assertEquals(listOf(SONG_A, SONG_B), repository.getSongUrisByKey("MOOD"))
    }

    @Test
    fun `removing songs drops their keys and leaves the others`() = runBlocking<Unit> {
        repository.replaceForSong(SONG_A, listOf(CustomTagField("MOOD", "calm")))
        repository.replaceForSong(SONG_B, listOf(CustomTagField("MOOD", "loud")))

        repository.removeSongs(listOf(SONG_A))

        assertEquals(listOf(SONG_B), repository.getSongUrisByKey("MOOD"))
    }

    @Test
    fun `removing no songs changes nothing`() = runBlocking<Unit> {
        repository.replaceForSong(SONG_A, listOf(CustomTagField("MOOD", "calm")))

        repository.removeSongs(emptyList())

        assertNotNull(dao.getKeysForSong(SONG_A).singleOrNull())
    }

    @Test
    fun `clearing the index empties it`() = runBlocking<Unit> {
        repository.replaceForSong(SONG_A, listOf(CustomTagField("MOOD", "calm")))

        repository.clearAll()

        assertTrue(repository.observeKeyCounts().first().isEmpty())
    }

    @Test
    fun `asking for a key that cannot be a key finds nothing`() = runBlocking<Unit> {
        repository.replaceForSong(SONG_A, listOf(CustomTagField("MOOD", "calm")))

        assertTrue(repository.getSongUrisByKey("   ").isEmpty())
        assertTrue(repository.getSongUrisByKey("k".repeat(65)).isEmpty())
    }

    private companion object {
        const val SONG_A = """H:\Music\a.mp3"""
        const val SONG_B = """H:\Music\b.mp3"""
    }
}
