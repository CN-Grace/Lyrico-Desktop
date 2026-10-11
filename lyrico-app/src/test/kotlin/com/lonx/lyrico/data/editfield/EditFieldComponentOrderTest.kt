package com.lonx.lyrico.data.editfield

import kotlin.test.*
import kotlin.test.Test

class EditFieldComponentOrderTest {
    @Test
    fun `saved main and child order survive reload and reach both editors and matching`() {
        val original = EditFieldConfig().withAddedCustomTag("SOURCE")
            .withEnabled("album_peak", false)
        val changed = original.withBlockOrder(listOf("lyrics", "rating", "tag:SOURCE", "component:ReplayGain", "picture"))
            .withComponentOrder("component:ReplayGain", listOf("track_peak", "track_gain"))
        val restored = EditFieldConfigJson.decode(EditFieldConfigJson.from(changed).encode(), null, null)
        EditFieldScene.entries.forEach { scene ->
            val blocks = restored.visibleFieldsForScene(scene).toEditFieldBlocks()
            assertEquals(listOf("lyrics", "rating", "tag:SOURCE", "component:ReplayGain", "picture"), blocks.take(5).map { it.key })
            assertEquals(listOf("track_peak", "track_gain", "album_gain", "reference_loudness"),
                blocks.single { it.isComposite }.fields.map { it.code })
        }
        assertEquals(original.overrides, restored.overrides)
        assertEquals(listOf("lyrics", "rating", "track_peak", "track_gain", "album_gain", "reference_loudness", "picture").map {
            EditFieldRegistry.fieldMap.getValue(it).target
        }, restored.matchTargets().take(7))
    }

    @Test
    fun `member sorting preserves other blocks and ignores foreign duplicate and unknown codes`() {
        val original = EditFieldConfig().withAddedCustomTag("SOURCE")
            .withBlockOrder(listOf("component:ReplayGain", "tag:SOURCE", "picture"))
            .withEnabled("track_gain", false)
        val changed = original.withComponentOrder("component:ReplayGain",
            listOf("album_peak", "title", "album_peak", "missing", "track_peak"))
        val before = original.allFields.toEditFieldBlocks()
        val after = changed.allFields.toEditFieldBlocks()
        assertEquals(before.map { it.key }, after.map { it.key })
        assertEquals(before.drop(1), after.drop(1))
        assertEquals(listOf("album_peak", "track_peak", "track_gain", "album_gain", "reference_loudness"),
            after.first().fields.map { it.code })
        assertEquals(original.overrides, changed.overrides)
    }

    @Test
    fun `main reorder based on old keys preserves most recent member order and new tags`() {
        val initial = EditFieldConfig()
        val oldKeys = initial.allFields.toEditFieldBlocks().map { it.key }.reversed()
        val changed = initial.withComponentOrder("component:ReplayGain", listOf("album_gain", "track_gain", "track_peak", "album_peak", "reference_loudness"))
            .withAddedCustomTag("NEW").withBlockOrder(oldKeys + "unknown")
        assertEquals(listOf("album_gain", "track_gain", "track_peak", "album_peak", "reference_loudness"), changed.allFields.toEditFieldBlocks()
            .single { it.key == "component:ReplayGain" }.fields.map { it.code })
        assertEquals(changed.orderedCodes().last(), "tag:NEW")
        assertEquals(changed.orderedCodes().distinct(), changed.orderedCodes())
    }

    @Test
    fun `unknown or noncomponent member sort leaves config unchanged`() {
        val config = EditFieldConfig()
        assertEquals(config, config.withComponentOrder("missing", listOf("title")))
        assertEquals(config, config.withComponentOrder("title", listOf("album")))
    }
}
