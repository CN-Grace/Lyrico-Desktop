package com.lonx.lyrico.data.editfield

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lonx.lyrico.data.model.BatchMatchConfig
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.metadata.MetadataWriteMode
import kotlin.test.*
import kotlin.test.Test

class EditFieldMigrationTest {
    @Test
    fun `old group disable overrides child enable and survives first write`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("edit_field_visibility_overrides") to
                """{"values":{"basic_info":false,"basic_info.title":true,"credits_other.comment":true}}""",
            stringPreferencesKey("custom_tag_settings") to
                """{"visibleKeys":[" source ","SOURCE","MOOD"]}""",
        )
        val config = EditFieldConfigRepository.readConfig(prefs)
        assertFalse(config.isEnabled("title"))
        assertFalse(config.isEnabled("artist"))
        assertTrue(config.isEnabled("comment"))
        assertEquals(listOf("SOURCE", "MOOD"), config.customTags)
        assertTrue(config.isEnabled("tag:SOURCE"))
        EditFieldConfigRepository.writeConfig(prefs, config.withEnabled("tag:SOURCE", false))
        assertEquals(setOf("edit_field_config"), prefs.asMap().keys.map { it.name }.toSet())
        val reloaded = EditFieldConfigRepository.readConfig(prefs)
        assertFalse(reloaded.isEnabled("title"))
        assertFalse(reloaded.isEnabled("tag:SOURCE"))
    }

    @Test
    fun `v1 order flattens only recognized legacy codes and preserves dotted custom keys`() {
        val config = EditFieldConfigJson.decode(
            """{"version":1,"fieldOrder":["cover.rating","tag:MY.title","basic_info.title","cover.rating","fake.title"],"customTags":["MY.title"]}""",
            """{"values":{"cover.rating":false}}""", null,
        )
        assertEquals(listOf("rating", "tag:MY.TITLE", "title"), config.orderedCodes().take(3))
        assertFalse(config.isEnabled("rating"))
        assertEquals(23, config.orderedCodes().size)
    }

    @Test
    fun `v2 hidden custom tags survive migration and stale settings do not return after v3`() {
        val config = EditFieldConfigJson.decode(
            """{"version":2,"customTags":["SOURCE"],"fieldOrder":["tag:SOURCE","lyrics"]}""",
            """{"values":{"tag:SOURCE":false}}""", null,
        )
        assertFalse(config.isEnabled("tag:SOURCE"))
        val updated = config.withRemovedCustomTag("SOURCE").withAddedCustomTag("SOURCE")
        val restored = EditFieldConfigJson.decode(EditFieldConfigJson.from(updated).encode(),
            """{"values":{"title":false,"tag:SOURCE":false}}""",
            """{"visibleKeys":["OBSOLETE"]}""")
        assertTrue(restored.isEnabled("title"))
        assertTrue(restored.isEnabled("tag:SOURCE"))
        assertEquals(listOf("SOURCE"), restored.customTags)
    }

    @Test
    fun `reset persists defaults without resurrecting legacy keys`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("custom_tag_settings") to """{"visibleKeys":["SOURCE"]}""",
        )
        EditFieldConfigRepository.writeConfig(prefs, EditFieldConfig())
        assertEquals(EditFieldConfig(), EditFieldConfigJson.decode(prefs.asMap().values.single() as String, null, null)
            .copy(order = emptyList()))
    }

    @Test
    fun `malformed storage falls back without dropping valid old tags`() {
        val config = EditFieldConfigJson.decode("broken", "broken", """{"visibleKeys":["SOURCE"]}""")
        assertEquals(listOf("SOURCE"), config.customTags)
        assertTrue(config.isEnabled("title"))
    }

    @Test
    fun `legacy interleaved members render together across scenes and match targets`() {
        val config = EditFieldConfig().withAddedCustomTag("SOURCE")
            .withOrder(listOf("rating", "tag:SOURCE", "disc_number", "lyrics", "title", "track_gain", "picture"))
            .withEnabled("disc_number", false)
        val prefix = listOf("rating", "tag:SOURCE", "lyrics", "title", "track_gain", "track_peak")
        EditFieldScene.entries.forEach { scene ->
            assertEquals(prefix, config.visibleFieldsForScene(scene).take(6).map { it.code })
        }
        assertEquals(listOf(MetadataFieldTarget.RATING, MetadataFieldTarget.LYRICS, MetadataFieldTarget.TITLE,
            MetadataFieldTarget.REPLAY_GAIN_TRACK_GAIN, MetadataFieldTarget.REPLAY_GAIN_TRACK_PEAK),
            config.matchTargets().take(5))
    }

    @Test
    fun `hidden match targets cannot be written even when saved match mode enables them`() {
        val fields = EditFieldConfig().withEnabled("title", false)
        val match = BatchMatchConfig(mapOf(MetadataFieldTarget.TITLE to MetadataWriteMode.OVERWRITE,
            MetadataFieldTarget.ARTIST to MetadataWriteMode.SUPPLEMENT,
            MetadataFieldTarget.CUSTOM to MetadataWriteMode.OVERWRITE))
        val effective = match.restrictedTo(fields.matchTargets().toSet())
        assertEquals(MetadataWriteMode.DISABLED, effective.targetModes[MetadataFieldTarget.TITLE])
        assertEquals(MetadataWriteMode.DISABLED, effective.targetModes[MetadataFieldTarget.CUSTOM])
        assertEquals(MetadataWriteMode.SUPPLEMENT, effective.targetModes[MetadataFieldTarget.ARTIST])
        assertEquals(MetadataWriteMode.OVERWRITE, match.targetModes[MetadataFieldTarget.TITLE])
    }
    @Test
    fun `v4 detached component switches migrate and replay gain master persists`() {
        val config = EditFieldConfigJson.decode(
            """{"version":4,"componentOverrides":{"component:Cover":false,"component:Lyrics":false,"component:ReplayGain":false},"overrides":{"track_peak":false}}""", null, null)
        assertFalse(config.isEnabled("picture"))
        assertFalse(config.isEnabled("rating"))
        assertFalse(config.isEnabled("lyrics"))
        assertFalse(config.isComponentEnabled("component:ReplayGain"))
        assertTrue(config.isEnabled("track_gain"))
        assertFalse(config.isEnabled("track_peak"))
        assertEquals(setOf("component:ReplayGain"), config.componentOverrides.keys)
        val restored = EditFieldConfigJson.decode(EditFieldConfigJson.from(config).encode(), null, null)
        assertEquals(config.componentOverrides, restored.componentOverrides)
        assertEquals(config.overrides, restored.overrides)
    }

    @Test
    fun `v3 defaults replay gain master on and retains member choices`() {
        val config = EditFieldConfigJson.decode("""{"version":3,"overrides":{"track_peak":false}}""", null, null)
        assertTrue(config.isComponentEnabled("component:ReplayGain"))
        assertFalse(config.isEnabled("track_peak"))
    }
}
