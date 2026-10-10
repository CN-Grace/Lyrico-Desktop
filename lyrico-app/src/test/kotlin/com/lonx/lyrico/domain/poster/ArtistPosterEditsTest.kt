package com.lonx.lyrico.domain.poster

import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.AudioPictureType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 艺术家海报的纯数据变换：一个艺术家只留一张（替换、重挂、交换归属），删除与还原按实例
 * （`===`）定位目标。
 *
 * 随 `ArtistPosterEdits` 从 Android 测试树迁入：只把 `org.junit.Assert` 的断言换成
 * `kotlin.test`，用例与断言一字未改。
 */
class ArtistPosterEditsTest {

    private fun artist(description: String, seed: Int = description.hashCode()) = AudioPicture(
        data = byteArrayOf(seed.toByte()),
        mimeType = "image/jpeg",
        description = description,
        pictureType = AudioPictureType.Artist.tagLibName
    )

    private fun cover(seed: Int = 1) = AudioPicture(
        data = byteArrayOf(seed.toByte()),
        mimeType = "image/jpeg",
        description = "",
        pictureType = AudioPictureType.FrontCover.tagLibName
    )

    // ------------------------------------------------------------ setFor

    @Test
    fun setForReplacesTheArtistPosterInPlace() {
        val a = artist("A")
        val b = artist("B")
        val cover = cover()
        val pictures = listOf(cover, a, b)
        val newA = artist("A", seed = 99)

        val result = ArtistPosterEdits.setFor(pictures, listOf("A", "B"), "A", newA)

        assertEquals(listOf(cover, newA, b), result)
        assertSame(b, result[2], "B 的海报必须原样保留")
    }

    @Test
    fun setForAppendsWhenTheArtistHasNoPosterYet() {
        val a = artist("A")
        val pictures = listOf(a)
        val newB = artist("B", seed = 7)

        assertEquals(listOf(a, newB), ArtistPosterEdits.setFor(pictures, listOf("A", "B"), "B", newB))
    }

    @Test
    fun setForAlsoCollapsesLeftoverDuplicatesOfThatArtist() {
        val first = artist("A", seed = 1)
        val duplicate = artist("a", seed = 2)
        val b = artist("B")
        val newA = artist("A", seed = 9)

        val result = ArtistPosterEdits.setFor(listOf(first, duplicate, b), listOf("A", "B"), "A", newA)

        assertEquals(listOf(newA, b), result)
    }

    @Test
    fun setForNeverTouchesTheFrontCover() {
        val cover = cover()
        val pictures = listOf(cover)

        val result = ArtistPosterEdits.setFor(pictures, emptyList(), "", artist("", seed = 5))

        assertSame(cover, result[0])
        assertEquals(2, result.size)
    }

    // ------------------------------------------------------------ remove / replaceData

    @Test
    fun removeAndReplaceDataOnlyTouchTheGivenInstance() {
        val first = artist("A", seed = 1)
        val lookalike = artist("A", seed = 1) // 数据完全相同，但只有首实例是目标
        val pictures = listOf(first, lookalike)

        assertEquals(listOf(lookalike), ArtistPosterEdits.remove(pictures, first))

        val replaced = ArtistPosterEdits.replaceData(pictures, first, byteArrayOf(42), "image/png")
        assertEquals(byteArrayOf(42).toList(), replaced[0].data.toList())
        assertEquals("image/png", replaced[0].mimeType)
        assertSame(lookalike, replaced[1])
    }

    @Test
    fun swapOwnersExchangesTheTwoArtistsWithoutLosingEitherPicture() {
        val a = artist("A", seed = 1)
        val c = artist("C", seed = 2)
        val pictures = listOf(a, c)

        val result = ArtistPosterEdits.swapOwners(pictures, listOf("A", "C"), target = c, artistName = "A")

        assertEquals(2, result.size)
        assertEquals("A", result[1].description)
        assertEquals("C", result[0].description)
        // 改描述必然产生新实例，但两张图都还在（谁都没丢）
        assertEquals(a.data.toList(), result[0].data.toList())
        assertEquals(c.data.toList(), result[1].data.toList())
    }

    @Test
    fun swapOwnersSendsTheTargetsOldDescriptionToTheOtherPicture() {
        // 目标原本没有归属（描述为空）：另一张换过去也变成没有归属，而不是被丢掉
        val unbound = artist("", seed = 1)
        val a = artist("A", seed = 2)

        val result = ArtistPosterEdits.swapOwners(
            pictures = listOf(unbound, a),
            artistNames = listOf("A"),
            target = unbound,
            artistName = "A"
        )

        assertEquals("A", result[0].description)
        assertEquals("", result[1].description)
    }

    @Test
    fun swapOwnersIsANoOpWhenTheArtistHasNoPoster() {
        val a = artist("A", seed = 1)
        val pictures = listOf(a)

        assertSame(pictures, ArtistPosterEdits.swapOwners(pictures, listOf("A", "B"), a, "B"))
    }

    // ------------------------------------------------------------ reassign

    @Test
    fun reassignMovesTheWholeUnknownGroupAndDropsTheTargetsOldPoster() {
        val unknown = artist("C", seed = 1)   // 描述对不上任何现有艺术家
        val sameUnknown = artist("C", seed = 2)
        val existingA = artist("A", seed = 3)
        val pictures = listOf(existingA, unknown, sameUnknown)

        val result = ArtistPosterEdits.reassign(pictures, listOf("A", "B"), unknown, "A")

        assertEquals(1, result.size)
        // 改描述必然产生新实例，但图片数据仍是原来那张
        assertEquals("A", result[0].description)
        assertEquals(unknown.data.toList(), result[0].data.toList())
        assertTrue(result.none { it === existingA }, "A 原来的海报被顶掉")
        assertTrue(result.none { it === sameUnknown }, "同组的另一张也被合并")
    }

    @Test
    fun reassignKeepsOtherArtistsUntouched() {
        val b = artist("B")
        val unknown = artist("C", seed = 1)
        val pictures = listOf(b, unknown)

        val result = ArtistPosterEdits.reassign(pictures, listOf("A", "B"), unknown, "A")

        assertSame(b, result[0])
        assertEquals("A", result[1].description)
    }

    // ------------------------------------------------------------ revertAll

    @Test
    fun revertAllRestoresArtistPostersAndKeepsTheCover() {
        val cover = cover()
        val originalA = artist("A", seed = 1)
        val editedA = artist("A", seed = 9)
        val pictures = listOf(cover, editedA)

        val result = ArtistPosterEdits.revertAll(pictures, listOf(cover, originalA))

        assertSame(cover, result[0])
        assertSame(originalA, result[1])
    }

    @Test
    fun revertAllOnAnEmptiedListBringsTheOriginalsBack() {
        val cover = cover()
        val originalA = artist("A", seed = 1)

        val result = ArtistPosterEdits.revertAll(listOf(cover), listOf(cover, originalA))

        assertEquals(2, result.size)
        assertSame(originalA, result[1])
    }

    /**
     * 回归：还原只换艺术家图片，不该动用户当前的排列。
     * 用户把艺术家海报和封面的顺序对调过，还原后非艺术家图片必须留在原处。
     */
    @Test
    fun revertAllKeepsTheCurrentOrderOfOtherPictures() {
        val originalArtist = artist("A", seed = 1)
        val cover = cover()
        val editedArtist = artist("A", seed = 9)
        // 用户把艺术家海报放在封面之前
        val pictures = listOf(editedArtist, cover)
        val original = listOf(originalArtist, cover)

        val result = ArtistPosterEdits.revertAll(pictures, original)

        assertEquals(listOf(originalArtist, cover), result)
    }

    @Test
    fun revertAllKeepsOrderWhenTheArtistPosterComesLast() {
        val originalArtist = artist("A", seed = 1)
        val cover = cover()
        val original = listOf(cover, originalArtist)

        val result = ArtistPosterEdits.revertAll(listOf(cover, artist("A", seed = 9)), original)

        assertEquals(original, result)
    }

    @Test
    fun revertAllKeepsTheOriginalLayoutWhenTheUserAddedPictures() {
        val originalArtist = artist("A", seed = 1)
        val cover = cover(seed = 1)
        val added = cover(seed = 2)
        val original = listOf(originalArtist, cover)

        val result = ArtistPosterEdits.revertAll(listOf(cover, added, artist("A", seed = 9)), original)

        // 艺术家海报回到封面之前（原始布局），用户新加的图跟在封面后面
        assertEquals(listOf(originalArtist, cover, added), result)
    }

    @Test
    fun revertAllAfterRemovingTheCoverDropsIt() {
        val originalArtist = artist("A", seed = 1)
        val cover = cover()
        val original = listOf(originalArtist, cover)

        val result = ArtistPosterEdits.revertAll(listOf(artist("A", seed = 9)), original)

        assertEquals(listOf(originalArtist), result)
    }

    @Test
    fun revertAllDoesNotMoveTheBackPictureIntoARemovedFrontCoverSlot() {
        val frontCover = cover(seed = 1)
        val originalArtist = artist("A", seed = 2)
        val backPicture = AudioPicture(
            data = byteArrayOf(3),
            mimeType = "image/jpeg",
            description = "back",
            pictureType = AudioPictureType.BackCover.tagLibName
        )
        val original = listOf(frontCover, originalArtist, backPicture)
        val editedArtist = artist("A", seed = 9)

        val result = ArtistPosterEdits.revertAll(
            pictures = listOf(editedArtist, backPicture),
            original = original
        )

        assertEquals(listOf(originalArtist, backPicture), result)
    }

    /**
     * 原始布局里艺术家海报在封面之前，还原后它也要回到封面之前。
     *
     * 就地替换（把艺术家位换回原图）会得到 `[新封面, 艺术家]`，布局与原始相反；对没有标准
     * 封面的文件，`frontCoverOrFallback()` 会退回「第一张图」，布局变了那一页就换成别的图了。
     */
    @Test
    fun revertAllPutsAnOriginallyLeadingArtistPosterBackBeforeTheCover() {
        val originalArtist = artist("A", seed = 1)
        val originalCover = cover(seed = 2)
        val replacementCover = cover(seed = 3)
        val original = listOf(originalArtist, originalCover)

        val result = ArtistPosterEdits.revertAll(
            pictures = listOf(replacementCover, artist("A", seed = 9)),
            original = original
        )

        assertEquals(listOf(originalArtist, replacementCover), result)
    }

    /**
     * 回归（真实操作链）：A、C 各有一张海报 → 把 C 重挂到 A（A 的原图被顶掉）→ 还原。
     * 逐张启发式还原只能找回 C，A 永远回不来；整组还原必须两张都回来。
     */
    @Test
    fun revertAllBringsBackThePosterThatReassignDropped() {
        val originalA = artist("A", seed = 1)
        val originalC = artist("C", seed = 2)
        val originals = listOf(originalA, originalC)

        val afterReassign = ArtistPosterEdits.reassign(
            pictures = originals,
            artistNames = listOf("A", "C"),
            target = originalC,
            artistName = "A"
        )
        assertEquals(1, afterReassign.size, "重挂后只剩改挂过来的那一张")

        val reverted = ArtistPosterEdits.revertAll(afterReassign, originals)

        assertEquals(originals, reverted)
    }

    /**
     * 回归（真实操作链）：重挂之后再裁剪，两个字段都变了。启发式匹配在这里必然猜错
     * （按描述会命中目标艺术家原来的海报），整组还原则与原图逐字段一致。
     */
    @Test
    fun revertAllAfterReassignThenCropRestoresBothOriginals() {
        val originalA = artist("A", seed = 1)
        val originalC = artist("C", seed = 2)
        val originals = listOf(originalA, originalC)

        val reassigned = ArtistPosterEdits.reassign(originals, listOf("A", "C"), originalC, "A")
        val cropped = ArtistPosterEdits.replaceData(
            pictures = reassigned,
            target = reassigned.single(),
            data = byteArrayOf(88),
            mimeType = "image/jpeg"
        )

        val reverted = ArtistPosterEdits.revertAll(cropped, originals)

        assertEquals(originals, reverted)
    }
}
