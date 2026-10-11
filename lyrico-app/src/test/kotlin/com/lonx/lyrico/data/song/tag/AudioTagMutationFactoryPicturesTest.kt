package com.lonx.lyrico.data.song.tag

import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.audiotag.model.AudioTagData
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 换封面与「删光图片」的交互。
 *
 * `picUrl` 非空时不会走整份图片列表，而是让下游拿一个「其余图片」的基准再插入封面；
 * 基准一旦被当成 `null`，下游就会退回磁盘上的旧图片，把用户删掉的海报又写回去。
 */
class AudioTagMutationFactoryPicturesTest {

    private fun artistPicture(seed: Int) = AudioPicture(
        data = byteArrayOf(seed.toByte()),
        mimeType = "image/jpeg",
        description = "A",
        pictureType = AudioPictureType.Artist.tagLibName
    )

    private fun replacePictureUpdate(
        pictures: List<AudioPicture>,
        picUrl: String,
        picturesAuthored: Boolean
    ): PictureUpdate.ReplacePicture =
        AudioTagMutationFactory.fromAudioTagData(
            data = AudioTagData(pictures = pictures, picUrl = picUrl),
            mode = AudioTagMutationMode.Overwrite,
            picturesAuthored = picturesAuthored
        ).pictureUpdate as PictureUpdate.ReplacePicture

    @Test
    fun authoredEmptyListStaysTheBaseWhenReplacingTheCover() {
        // 用户把艺术家海报删光后又换封面：基准必须是空的，不能退回磁盘上的旧图片
        val update = replacePictureUpdate(
            pictures = emptyList(),
            picUrl = "https://example.com/cover.jpg",
            picturesAuthored = true
        )

        assertNotNull(update.basePictures, "空列表是有效基准")
        assertTrue(update.basePictures!!.isEmpty(), "不能退回磁盘旧图片")
    }

    @Test
    fun unauthoredEmptyListFallsBackToDiskPictures() {
        // 读取失败等情况没有拿到图片，此时绝不能把文件里的图片抹掉
        val update = replacePictureUpdate(
            pictures = emptyList(),
            picUrl = "https://example.com/cover.jpg",
            picturesAuthored = false
        )

        assertNull(update.basePictures, "没读到图片时沿用磁盘内容")
    }

    @Test
    fun authoredRemainingPicturesAreUsedAsTheBase() {
        val remaining = artistPicture(1)

        val update = replacePictureUpdate(
            pictures = listOf(remaining),
            picUrl = "https://example.com/cover.jpg",
            picturesAuthored = true
        )

        assertEquals(listOf(remaining), update.basePictures)
    }

    @Test
    fun removingTheCoverKeepsTheAuthoredEmptyBase() {
        val update = AudioTagMutationFactory.fromAudioTagData(
            data = AudioTagData(pictures = emptyList(), picUrl = ""),
            mode = AudioTagMutationMode.Overwrite,
            picturesAuthored = true
        ).pictureUpdate as PictureUpdate.RemovePicture

        assertEquals(AudioPictureType.FrontCover, update.type)
        assertNotNull(update.basePictures)
        assertTrue(update.basePictures!!.isEmpty())
    }

    @Test
    fun emptiedPicturesAreWrittenWithoutTouchingPicUrl() {
        // 没有换过封面（picUrl 为 null）时，删空也要落盘
        val update = AudioTagMutationFactory.fromAudioTagData(
            data = AudioTagData(pictures = emptyList()),
            mode = AudioTagMutationMode.Overwrite,
            picturesAuthored = true
        ).pictureUpdate

        assertEquals(PictureUpdate.ReplaceAll(emptyList()), update)
    }

    @Test
    fun untouchedPicturesAreLeftAlone() {
        val update = AudioTagMutationFactory.fromAudioTagData(
            data = AudioTagData(pictures = emptyList()),
            mode = AudioTagMutationMode.Overwrite,
            picturesAuthored = false
        ).pictureUpdate

        assertEquals(PictureUpdate.Unchanged, update)
    }

    @Test
    fun patchModeNeverTouchesPictures() {
        val update = AudioTagMutationFactory.fromAudioTagData(
            data = AudioTagData(pictures = listOf(artistPicture(1))),
            mode = AudioTagMutationMode.Patch,
            picturesAuthored = true
        ).pictureUpdate

        assertEquals(PictureUpdate.Unchanged, update)
    }
}
