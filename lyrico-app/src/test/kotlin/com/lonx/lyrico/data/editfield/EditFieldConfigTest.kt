package com.lonx.lyrico.data.editfield

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class EditFieldConfigTest {

    @Test
    fun `default config keeps registry order and default visibility`() {
        val config = EditFieldConfig()

        assertEquals(EditFieldRegistry.defaultOrder, config.orderedCodes())

        assertEquals(listOf(
            "picture", "rating", "title", "artist", "album_artist", "album", "date", "language", "genre",
            "track_number", "disc_number", "lyricist", "composer", "copyright", "comment",
            "track_gain", "track_peak", "album_gain", "album_peak", "reference_loudness", "lyrics", "lyrics_offset",
        ), config.orderedCodes())
        assertEquals(22, config.allFields.size)
        assertTrue(config.allFields.all { config.isEffectivelyEnabled(it) })
    }

    @Test
    fun `override wins over the registry default`() {
        val config = EditFieldConfig().withEnabled("copyright", false)

        assertFalse(config.isEnabled("copyright"))
        assertTrue(config.isEnabled("title"))
    }

    @Test
    fun `scene scope filters fields`() {
        val config = EditFieldConfig()

        val single = config.visibleFieldCodesForScene(EditFieldScene.SingleEdit)
        val batch = config.visibleFieldCodesForScene(EditFieldScene.BatchEdit)

        // 歌词偏移只在批量编辑页出现。
        assertFalse(single.contains("lyrics_offset"))
        assertTrue(batch.contains("lyrics_offset"))
    }

    @Test
    fun `disabled field leaves its scope`() {
        val config = EditFieldConfig().withEnabled("lyrics", false)

        assertFalse(config.visibleFieldCodesForScene(EditFieldScene.SingleEdit).contains("lyrics"))
        assertFalse(config.visibleFieldCodesForScene(EditFieldScene.BatchEdit).contains("lyrics"))
    }

    @Test
    fun `custom tag is appended, enabled and removed cleanly`() {
        val config = EditFieldConfig()
            .withAddedCustomTag("SOURCE")
            .withAddedCustomTag("MOOD")

        val code = EditFieldRegistry.customTagCode("SOURCE")
        assertEquals(EditFieldRegistry.customTagKeyOf(code), "SOURCE")
        assertTrue(config.isEnabled(code))
        assertEquals(listOf("SOURCE", "MOOD"), config.customTags)
        assertEquals(
            EditFieldRegistry.defaultOrder +
                listOf(code, EditFieldRegistry.customTagCode("MOOD")),
            config.orderedCodes(),
        )

        // 再添加同一个键不会重复。
        assertEquals(config.customTags, config.withAddedCustomTag("SOURCE").customTags)

        val removed = config.withRemovedCustomTag("SOURCE")
        assertFalse(removed.orderedCodes().contains(code))
        assertTrue(removed.orderedCodes().contains(EditFieldRegistry.customTagCode("MOOD")))
    }

    @Test
    fun `custom tags are visible in both scenes`() {
        val config = EditFieldConfig().withAddedCustomTag("SOURCE")
        val code = EditFieldRegistry.customTagCode("SOURCE")

        assertTrue(config.visibleFieldCodesForScene(EditFieldScene.SingleEdit).contains(code))
        assertTrue(config.visibleFieldCodesForScene(EditFieldScene.BatchEdit).contains(code))
        assertEquals(listOf("SOURCE"), config.visibleCustomTagKeys(EditFieldScene.BatchEdit))
    }

    @Test
    fun `order changes apply and unknown codes are ignored`() {
        val config = EditFieldConfig()
        val reversed = config.orderedCodes().reversed()

        val reordered = config.withOrder(reversed + "not_a_field")

        assertEquals(reversed, reordered.orderedCodes())
    }

    @Test
    fun `visible field list is ordered by the configured order`() {
        val config = EditFieldConfig()
        val reversed = config.orderedCodes().reversed()
        val reordered = config.withOrder(reversed)

        val visible = reordered.visibleFieldsForScene(EditFieldScene.SingleEdit).map { it.code }

        // 单曲页不显示批量专用的歌词偏移；其余字段全部开启且遵循配置顺序。
        assertEquals(
            reversed.filterNot { it == "lyrics_offset" },
            visible,
        )
    }

    @Test
    fun `visible field list carries the render kind for the editor loop`() {
        val config = EditFieldConfig()

        val byCode = config.visibleFieldsForScene(EditFieldScene.SingleEdit)
            .associateBy { it.code }

        assertEquals(EditFieldKind.Cover, byCode.getValue("picture").kind)
        assertEquals(EditFieldKind.Lyrics, byCode.getValue("lyrics").kind)
        assertEquals(EditFieldKind.ReplayGain, byCode.getValue("track_gain").kind)
        assertEquals(EditFieldKind.PersonList, byCode.getValue("artist").kind)
        assertEquals(EditFieldKind.Text, byCode.getValue("title").kind)
    }

    @Test
    fun `registery has no duplicate codes or orders and never collides with the custom prefix`() {
        EditFieldRegistry.validate()
    }
}
