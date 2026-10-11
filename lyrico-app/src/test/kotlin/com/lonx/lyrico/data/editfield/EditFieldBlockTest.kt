package com.lonx.lyrico.data.editfield

import kotlin.test.*
import kotlin.test.Test

class EditFieldBlockTest {
    @Test
    fun `only sixteen text fields use the common text renderer`() {
        assertEquals(16, EditFieldRegistry.fields.count { it.simpleTextInput })
        assertFalse(EditFieldRegistry.fieldMap.getValue("rating").simpleTextInput)
    }

    @Test
    fun `cover rating and lyrics remain independent while replay gain stays together`() {
        val config = EditFieldConfig().withAddedCustomTag("SOURCE").withOrder(listOf(
            "rating", "tag:SOURCE", "picture", "title", "lyrics", "track_peak", "album", "track_gain"))
        EditFieldScene.entries.forEach { scene ->
            val blocks = config.visibleFieldsForScene(scene).toEditFieldBlocks()
            assertEquals(listOf("rating", "tag:SOURCE", "picture", "title", "lyrics", "component:ReplayGain", "album"),
                blocks.take(7).map { it.key })
            assertEquals(1, blocks.count { it.isComposite })
            assertEquals(listOf("track_peak", "track_gain", "album_gain", "album_peak", "reference_loudness"),
                blocks.single { it.isComposite }.fields.map { it.code })
        }
    }

    @Test
    fun `hidden first replay gain member does not move component`() {
        val config = EditFieldConfig().withOrder(listOf("track_gain", "title", "track_peak"))
            .withEnabled("track_gain", false)
        val blocks = config.visibleFieldsForScene(EditFieldScene.SingleEdit).toEditFieldBlocks()
        assertEquals(listOf("component:ReplayGain", "title"), blocks.take(2).map { it.key })
        assertEquals(blocks.first().fields.first().code, "track_peak")
    }

    @Test
    fun `rating remains visible independently of cover`() {
        val config = EditFieldConfig().withEnabled("picture", false)
        val fields = config.visibleFieldsForScene(EditFieldScene.SingleEdit).map { it.code }
        assertTrue("rating" in fields)
        assertFalse("picture" in fields)
    }

    @Test
    fun `master switch gates component without changing child selections`() {
        val original = EditFieldConfig().withEnabled("track_peak", false)
        val disabled = original.withComponentEnabled("component:ReplayGain", false)
        assertEquals(original.overrides, disabled.overrides)
        EditFieldScene.entries.forEach { scene ->
            assertFalse(disabled.visibleFieldsForScene(scene).any { it.kind == EditFieldKind.ReplayGain })
        }
        assertEquals(original.visibleFieldsForScene(EditFieldScene.SingleEdit),
            disabled.withComponentEnabled("component:ReplayGain", true).visibleFieldsForScene(EditFieldScene.SingleEdit))
        assertTrue(disabled.matchTargets().none { target ->
            EditFieldRegistry.fields.any { it.kind == EditFieldKind.ReplayGain && it.target == target }
        })
    }

    @Test
    fun `enabled master with all children off produces no component`() {
        var config = EditFieldConfig()
        EditFieldRegistry.fields.filter { it.kind == EditFieldKind.ReplayGain }
            .forEach { config = config.withEnabled(it.code, false) }
        assertTrue(config.isComponentEnabled("component:ReplayGain"))
        assertFalse(config.visibleFieldsForScene(EditFieldScene.SingleEdit).toEditFieldBlocks().any { it.isComposite })
    }

    @Test
    fun `lyrics offset stays independent and batch only`() {
        val config = EditFieldConfig().withOrder(listOf("lyrics_offset", "lyrics", "title"))
        assertEquals(config.visibleFieldsForScene(EditFieldScene.SingleEdit).first().code, "lyrics")
        assertEquals(config.visibleFieldsForScene(EditFieldScene.BatchEdit).first().code, "lyrics_offset")
    }
}
