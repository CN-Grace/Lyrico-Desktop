package com.lonx.lyrico.domain.poster

import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.AudioPictureType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 艺术家海报位：内嵌海报和外置海报文件夹是同一个「一位艺术家一张海报」的两个来源，
 * 必须按同一套归属规则分配，否则多艺术家歌曲一张外置海报都用不上。
 *
 * 随 `ArtistPosterPages` 从 Android 测试树迁入：只把 `org.junit.Assert` 的断言换成
 * `kotlin.test`，用例与断言一字未改。
 */
class ArtistPosterPagesTest {

    private fun artist(description: String, seed: Int = description.hashCode()) = AudioPicture(
        data = byteArrayOf(seed.toByte()),
        mimeType = "image/jpeg",
        description = description,
        pictureType = AudioPictureType.Artist.tagLibName
    )

    private fun cover() = AudioPicture(
        data = byteArrayOf(9),
        mimeType = "image/jpeg",
        description = "",
        pictureType = AudioPictureType.FrontCover.tagLibName
    )

    @Test
    fun everyArtistWithoutAnEmbeddedPosterGetsItsOwnSlot() {
        // A 有内嵌海报，B 没有 → B 要有自己的一页去外置海报文件夹里找 B.jpg
        val pages = ArtistPosterPages.build(
            pictures = listOf(cover(), artist("A")),
            original = listOf(cover(), artist("A")),
            artistNames = listOf("A", "B")
        )

        assertEquals(listOf("A", "B"), pages.map { it.artistName })
        assertSame(AudioPictureType.Artist.tagLibName, pages[0].entry?.picture?.pictureType, "A 用内嵌海报")
        assertFalse(pages[0].showExternalPoster, "A 不需要外置海报")
        assertEquals(null, pages[1].entry, "B 没有内嵌海报")
        assertTrue(pages[1].showExternalPoster, "B 去外置海报文件夹找")
    }

    @Test
    fun slotsFollowTheArtistFieldOrder() {
        val pages = ArtistPosterPages.build(
            pictures = listOf(cover(), artist("C")),
            original = emptyList(),
            artistNames = listOf("A", "B", "C")
        )

        assertEquals(listOf("A", "B", "C"), pages.map { it.artistName })
    }

    @Test
    fun slotOfADeletedPosterStaysEmptyInsteadOfFallingBackToTheFolder() {
        // 原始有 A 的海报，用户删掉了：留空位（可以点进去重新添加），不要又从文件夹变出一张
        val pages = ArtistPosterPages.build(
            pictures = listOf(cover()),
            original = listOf(cover(), artist("A")),
            artistNames = listOf("A")
        )

        assertEquals(1, pages.size)
        assertEquals("A", pages[0].artistName)
        assertEquals(null, pages[0].entry)
        assertFalse(pages[0].showExternalPoster, "不能从外置海报把删掉的图变回来")
    }

    @Test
    fun aFolderBackedSlotCanStillFallBackWhenTheOriginalNeverHadOne() {
        val pages = ArtistPosterPages.build(
            pictures = listOf(cover()),
            original = listOf(cover()),
            artistNames = listOf("A")
        )

        assertTrue(pages[0].showExternalPoster)
    }

    @Test
    fun leftoversOfOneArtistAreAllListedRatherThanHidden() {
        val first = artist("A", seed = 1)
        val duplicate = artist("a", seed = 2)

        val pages = ArtistPosterPages.build(
            pictures = listOf(first, duplicate),
            original = listOf(first, duplicate),
            artistNames = listOf("A")
        )

        assertEquals(2, pages.size)
        assertSame(first, pages[0].entry?.picture)
        assertSame(duplicate, pages[1].entry?.picture)
    }

    @Test
    fun picturesThatMatchNoArtistGetTheirOwnSlot() {
        val orphan = artist("C", seed = 1)

        val pages = ArtistPosterPages.build(
            pictures = listOf(orphan),
            original = listOf(orphan),
            artistNames = listOf("A", "B")
        )

        assertEquals(listOf("A", "B", "C"), pages.map { it.artistName })
        assertTrue(pages[2].entry?.isUnmatched == true, "对不上的那张单独占一页，供用户重新指定归属")
        assertTrue(pages[0].showExternalPoster && pages[1].showExternalPoster, "A、B 各自去文件夹找")
    }

    @Test
    fun aBlankArtistFieldStillLeavesOneSlotToAddInto() {
        val pages = ArtistPosterPages.build(
            pictures = listOf(cover()),
            original = listOf(cover()),
            artistNames = emptyList()
        )

        assertEquals(1, pages.size)
        assertEquals("", pages[0].artistName)
        assertTrue(pages[0].showExternalPoster, "没有艺术家时也要留一个位，否则没地方添加")
    }

    @Test
    fun anUntaggedPosterFollowsTheOnlyArtist() {
        val untagged = artist("")

        val pages = ArtistPosterPages.build(
            pictures = listOf(untagged),
            original = listOf(untagged),
            artistNames = listOf("A")
        )

        assertEquals(1, pages.size)
        assertEquals("A", pages[0].artistName)
        assertSame(untagged, pages[0].entry?.picture)
        assertFalse(pages[0].showExternalPoster)
    }

    @Test
    fun anUntaggedPosterIsAmbiguousWithSeveralArtists() {
        val untagged = artist("", seed = 5)

        val pages = ArtistPosterPages.build(
            pictures = listOf(untagged),
            original = listOf(untagged),
            artistNames = listOf("A", "B")
        )

        assertEquals(3, pages.size)
        assertEquals(listOf("A", "B", ""), pages.map { it.artistName })
        assertTrue(pages[2].entry?.isUnmatched == true, "无法归属的那张单独一页")
        assertTrue(pages[0].showExternalPoster && pages[1].showExternalPoster, "A、B 仍各自去文件夹找")
    }
}
