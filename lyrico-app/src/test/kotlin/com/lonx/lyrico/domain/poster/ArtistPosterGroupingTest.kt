package com.lonx.lyrico.domain.poster

import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.AudioPictureType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 一个艺术家只保留一张海报：归属（艺术家名，或对不上时标签里的描述）就是操作目标。
 *
 * 随 [ArtistPosterGrouping] 从 Android 测试树迁入：只把 `org.junit.Assert` 的断言换成
 * `kotlin.test`，用例与断言一字未改。
 */
class ArtistPosterGroupingTest {

    private fun artistPicture(
        description: String,
        type: AudioPictureType = AudioPictureType.Artist,
        seed: Int = description.hashCode()
    ) = AudioPicture(
        data = byteArrayOf(seed.toByte()),
        mimeType = "image/jpeg",
        description = description,
        pictureType = type.tagLibName
    )

    private fun frontCover() = AudioPicture(
        data = byteArrayOf(9),
        mimeType = "image/jpeg",
        description = "whatever",
        pictureType = AudioPictureType.FrontCover.tagLibName
    )

    @Test
    fun keepsEveryArtistPictureInTagOrder() {
        val pictures = listOf(
            frontCover(),
            artistPicture("A"),
            artistPicture("B", AudioPictureType.LeadArtist),
            artistPicture("C")
        )

        val entries = ArtistPosterGrouping.entries(pictures, listOf("A", "B", "C"))

        assertEquals(3, entries.size)
        assertEquals(listOf("A", "B", "C"), entries.map { it.artist })
        assertEquals(listOf("A", "B", "C"), entries.map { it.description })
        assertTrue(entries.none { it.isUnmatched })
    }

    @Test
    fun matchesDescriptionIgnoringCaseAndSurroundingSpace() {
        val entries = ArtistPosterGrouping.entries(
            pictures = listOf(artistPicture("  alice  ")),
            artistNames = listOf("Alice")
        )

        assertEquals("Alice", entries.single().artist)
        assertEquals("alice", entries.single().description)
        // 归属键忽略大小写与空白，写入时才能定位到同一张
        assertEquals("alice", entries.single().ownerKey)
    }

    @Test
    fun flagsDescriptionThatMatchesNoArtist() {
        val entries = ArtistPosterGrouping.entries(
            pictures = listOf(artistPicture("Alice"), artistPicture("Bob")),
            artistNames = listOf("Alice", "Carol")
        )

        assertEquals("Alice", entries[0].artist)
        assertEquals("Alice", entries[0].ownerName)
        assertTrue(entries[1].isUnmatched)
        // 对不上时退回描述本身，仍然是一个可定位、可重挂的归属
        assertEquals("Bob", entries[1].ownerName)
        assertEquals("bob", entries[1].ownerKey)
    }

    @Test
    fun reportsUnmatchedAfterTheArtistFieldIsEdited() {
        // 艺术家字段从 A/B 改成 A/C 之后，B 的海报就对不上了
        val entries = ArtistPosterGrouping.entries(
            pictures = listOf(artistPicture("A"), artistPicture("B")),
            artistNames = listOf("A", "C")
        )

        assertEquals(listOf("A", "B"), entries.map { it.ownerName })
        assertEquals(listOf(false, true), entries.map { it.isUnmatched })
    }

    @Test
    fun blankDescriptionFollowsTheOnlyArtist() {
        val entries = ArtistPosterGrouping.entries(
            pictures = listOf(artistPicture("")),
            artistNames = listOf("Alice")
        )

        assertEquals("Alice", entries.single().artist)
        assertEquals("alice", entries.single().ownerKey)
    }

    @Test
    fun blankDescriptionIsUnmatchedWhenArtistsAreAmbiguous() {
        val entries = ArtistPosterGrouping.entries(
            pictures = listOf(artistPicture("")),
            artistNames = listOf("Alice", "Bob")
        )

        assertTrue(entries.single().isUnmatched)
        assertEquals("", entries.single().ownerKey)
    }

    @Test
    fun ignoresNonArtistPictures() {
        val entries = ArtistPosterGrouping.entries(
            pictures = listOf(frontCover()),
            artistNames = listOf("Alice")
        )

        assertTrue(entries.isEmpty())
    }

    @Test
    fun duplicatePicturesOfOneArtistShareASingleOwnerKey() {
        // 早期版本允许同一艺术家追加多张，盘上仍可能存在；它们必须归到同一个归属键，
        // 这样重挂/替换才会一次性处理掉整批
        val entries = ArtistPosterGrouping.entries(
            pictures = listOf(
                artistPicture("A", seed = 1),
                artistPicture("B", seed = 2),
                artistPicture("a", seed = 3)
            ),
            artistNames = listOf("A", "B")
        )

        assertEquals(listOf("a", "b", "a"), entries.map { it.ownerKey })
        assertEquals(2, entries.count { it.ownerKey == "a" })
        assertEquals(listOf("A", "B"), entries.mapNotNull { it.artist }.distinct())
        assertFalse(entries.any { it.isUnmatched })
    }

    @Test
    fun countsPicturesPerArtistByOwnerKey() {
        val entries = ArtistPosterGrouping.entries(
            pictures = listOf(artistPicture("Alice"), artistPicture("alice"), artistPicture("Bob")),
            artistNames = listOf("Alice", "Bob")
        )

        assertEquals(2, ArtistPosterGrouping.countFor(entries, "Alice"))
        assertEquals(1, ArtistPosterGrouping.countFor(entries, "bob"))
        assertEquals(0, ArtistPosterGrouping.countFor(entries, "Carol"))
    }

    @Test
    fun picturesThatShareBytesButNotDescriptionAreNotEqual() {
        // 列表相等性曾经忽略描述，导致「改归属」不会被识别成一次修改
        assertNotEquals(artistPicture("A"), artistPicture("B"))
        assertEquals(artistPicture("A"), artistPicture("A"))
    }
}
