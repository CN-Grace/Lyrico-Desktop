package com.lonx.lyrico.data.model.metadata

import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.model.entity.SongEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `SearchResultApplier` 决定「插件返回的一堆字段里，哪些允许落到文件上」。
 *
 * 这一层是纯函数，但它是 MATCH_* 三个处理器的写入门槛：写多了会覆盖用户的标签，写少了匹配就是空转。
 * 所以这里逐条钉死三种写入模式、空白值、未知键、差量补丁与行→标签映射的实测行为。
 *
 * 注：`SongEntity.toAudioTagData()` 走的是 `trackerNumber` → `trackNumber` 这类改名映射，
 * 桌面端沿用 Android 的列名，测试里把这条映射固定住。
 */
class SearchResultApplierTest {

    private fun policy(vararg modes: Pair<MetadataFieldTarget, MetadataWriteMode>) =
        MetadataApplyPolicy(fieldModes = modes.toMap())

    @Test
    fun `overwrite replaces a value the file already had`() {
        val current = AudioTagData(album = "旧专辑")

        val applied = SearchResultApplier.applyFields(
            current = current,
            fields = mapOf("album" to "新专辑"),
            policy = policy(MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE)
        )

        assertEquals("新专辑", applied.album)
    }

    @Test
    fun `supplement fills a blank field and leaves a present one alone`() {
        val current = AudioTagData(title = "已有标题")

        val applied = SearchResultApplier.applyFields(
            current = current,
            fields = mapOf("title" to "插件标题", "album" to "插件专辑"),
            policy = policy(
                MetadataFieldTarget.TITLE to MetadataWriteMode.SUPPLEMENT,
                MetadataFieldTarget.ALBUM to MetadataWriteMode.SUPPLEMENT
            )
        )

        assertEquals("已有标题", applied.title, "supplement 不能覆盖已经有的值")
        assertEquals("插件专辑", applied.album, "supplement 要补上空白字段")
    }

    @Test
    fun `a blank field counts as blank even when it is only whitespace`() {
        val current = AudioTagData(album = "   ")

        val applied = SearchResultApplier.applyFields(
            current = current,
            fields = mapOf("album" to "插件专辑"),
            policy = policy(MetadataFieldTarget.ALBUM to MetadataWriteMode.SUPPLEMENT)
        )

        assertEquals("插件专辑", applied.album)
    }

    @Test
    fun `disabled writes nothing`() {
        val current = AudioTagData(album = "旧专辑", comment = "旧备注")

        val applied = SearchResultApplier.applyFields(
            current = current,
            fields = mapOf("album" to "新专辑", "comment" to "新备注"),
            policy = policy(MetadataFieldTarget.ALBUM to MetadataWriteMode.DISABLED)
        )

        assertEquals("旧专辑", applied.album)
        assertEquals("旧备注", applied.comment, "没在策略里的字段取默认 DISABLED")
    }

    @Test
    fun `an empty answer never clears a field even in overwrite mode`() {
        val current = AudioTagData(album = "旧专辑")

        val applied = SearchResultApplier.applyFields(
            current = current,
            fields = mapOf("album" to "", "comment" to "   "),
            policy = policy(
                MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE,
                MetadataFieldTarget.COMMENT to MetadataWriteMode.OVERWRITE
            )
        )

        assertEquals("旧专辑", applied.album, "插件返回空串不是「清空」的意思")
        assertNull(applied.comment)
    }

    @Test
    fun `a key the plugin invented is ignored`() {
        val current = AudioTagData(album = "旧专辑")

        val applied = SearchResultApplier.applyFields(
            current = current,
            fields = mapOf("album" to "新专辑", "fixture_secret" to "x"),
            policy = policy(MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE)
        )

        assertEquals("新专辑", applied.album)
    }

    @Test
    fun `a number that arrives as a tag string is parsed, a junk value leaves the field alone`() {
        val current = AudioTagData()

        val applied = SearchResultApplier.applyFields(
            current = current,
            fields = mapOf("track_number" to "3/12", "rating" to "五星"),
            policy = policy(
                MetadataFieldTarget.TRACK_NUMBER to MetadataWriteMode.OVERWRITE,
                MetadataFieldTarget.RATING to MetadataWriteMode.OVERWRITE
            )
        )

        assertEquals("3/12", applied.trackNumber, "音轨号保留插件原样字符串")
        assertNull(applied.rating, "解析不出数字时不写，而不是写 0")
    }

    @Test
    fun `buildPatch carries only the fields that actually changed`() {
        val current = AudioTagData(title = "山丘", album = "旧专辑")

        val patch = SearchResultApplier.buildPatch(
            current = current,
            fields = mapOf("title" to "山丘", "album" to "新专辑", "genre" to "民谣"),
            policy = policy(
                MetadataFieldTarget.TITLE to MetadataWriteMode.OVERWRITE,
                MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE,
                MetadataFieldTarget.GENRE to MetadataWriteMode.OVERWRITE
            )
        )

        assertEquals("新专辑", patch.album)
        assertEquals("民谣", patch.genre)
        assertNull(patch.title, "值没变的字段不能进补丁：补丁模式写下去会把别的字段冲掉")
    }

    @Test
    fun `buildPatch of an answer that changes nothing is completely empty`() {
        val current = AudioTagData(title = "山丘", album = "专辑")

        val patch = SearchResultApplier.buildPatch(
            current = current,
            fields = mapOf("title" to "山丘", "album" to "专辑"),
            policy = policy(
                MetadataFieldTarget.TITLE to MetadataWriteMode.SUPPLEMENT,
                MetadataFieldTarget.ALBUM to MetadataWriteMode.SUPPLEMENT
            )
        )

        assertEquals(AudioTagData(), patch, "没有可写的字段时补丁必须是全空，处理器据此跳过该文件")
    }

    @Test
    fun `a database row becomes the tag data the applier works on`() {
        val song = SongEntity(
            id = 7,
            folderId = 1,
            mediaId = 0,
            filePath = "H:\\Music\\山丘.mp3",
            fileName = "山丘.mp3",
            uri = "H:\\Music\\山丘.mp3",
            title = "山丘",
            artist = "李宗盛",
            album = "山丘",
            trackerNumber = "3/12",
            discNumber = 1,
            rating = 4,
            lyrics = "[00:01.00]越过山丘"
        )

        val data = song.toAudioTagData()

        assertEquals("山丘", data.title)
        assertEquals("李宗盛", data.artist)
        assertEquals("3/12", data.trackNumber, "桌面端列名是 trackerNumber，标签字段是 trackNumber")
        assertEquals(1, data.discNumber)
        assertEquals(4, data.rating)
        assertEquals("[00:01.00]越过山丘", data.lyrics)
    }
}
