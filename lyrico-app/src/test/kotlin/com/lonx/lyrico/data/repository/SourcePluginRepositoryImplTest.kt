package com.lonx.lyrico.data.repository

import com.lonx.lyrico.data.model.entity.SourcePluginEntity
import com.lonx.lyrico.data.model.entity.displayName
import com.lonx.lyrico.data.model.entity.isEnabledAnywhere
import com.lonx.lyrico.data.model.plugin.PluginSourceType
import com.lonx.lyrico.data.support.TestLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The installed source plugins, against a real database.
 *
 * The plugin table keeps three independent on/off switches and three independent orderings (metadata,
 * lyrics, cover) in the same row, and the repository's whole job is picking the right column for the
 * source type it was handed. Getting that wrong does not throw — it silently enables the wrong kind
 * of source, or scrambles the order of another kind — so each source type is asserted separately
 * while the other two are asserted unchanged.
 */
class SourcePluginRepositoryImplTest {

    private lateinit var library: TestLibrary
    private lateinit var repository: SourcePluginRepository

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        repository = SourcePluginRepositoryImpl(dao = library.database.sourcePluginDao())
    }

    @AfterTest
    fun tearDown() {
        library.close()
    }

    @Test
    fun `an installed plugin reads back exactly as it was written`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "net.example.a", name = "Source A"))

        val stored = repository.getPlugin("net.example.a")

        assertEquals(plugin(id = "net.example.a", name = "Source A"), stored)
    }

    @Test
    fun `installing the same plugin id again replaces it`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "net.example.a", name = "Source A", versionName = "1.0.0"))
        repository.upsertPlugin(plugin(id = "net.example.a", name = "Source A", versionName = "2.0.0"))

        assertEquals(listOf("2.0.0"), repository.getPlugins().map { it.versionName })
    }

    @Test
    fun `an unknown plugin is nothing, not an error`() = runBlocking<Unit> {
        assertNull(repository.getPlugin("net.example.none"))
    }

    @Test
    fun `plugins are listed by sort order and then by the name on screen`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "b", name = "Beta", sortOrder = 1))
        repository.upsertPlugin(plugin(id = "a", name = "Alpha", sortOrder = 0))
        repository.upsertPlugin(plugin(id = "c", name = "Gamma", sortOrder = 1))
        // A custom name is what the user sees, so it is what the fallback order has to use.
        repository.upsertPlugin(plugin(id = "d", name = "Zeta", sortOrder = 1, customName = "Aardvark"))

        assertEquals(
            listOf("a", "d", "b", "c"),
            repository.getPlugins().map { it.id },
        )
        assertEquals(listOf("a", "d", "b", "c"), repository.observePlugins().first().map { it.id })
    }

    @Test
    fun `enabling metadata leaves the lyrics and cover switches alone`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "a", name = "Alpha"))

        repository.setEnabled("a", PluginSourceType.METADATA, enabled = true)

        val stored = repository.getPlugin("a")!!
        assertTrue(stored.metadataEnabled)
        assertFalse(stored.lyricsEnabled)
        assertFalse(stored.coverEnabled)
    }

    @Test
    fun `enabling lyrics leaves the metadata and cover switches alone`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "a", name = "Alpha"))

        repository.setEnabled("a", PluginSourceType.LYRICS, enabled = true)

        val stored = repository.getPlugin("a")!!
        assertFalse(stored.metadataEnabled)
        assertTrue(stored.lyricsEnabled)
        assertFalse(stored.coverEnabled)
    }

    @Test
    fun `enabling cover leaves the metadata and lyrics switches alone`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "a", name = "Alpha"))

        repository.setEnabled("a", PluginSourceType.COVER, enabled = true)

        val stored = repository.getPlugin("a")!!
        assertFalse(stored.metadataEnabled)
        assertFalse(stored.lyricsEnabled)
        assertTrue(stored.coverEnabled)
    }

    @Test
    fun `switching a source off again works for every source type`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "a", name = "Alpha"))
        PluginSourceType.entries.forEach { repository.setEnabled("a", it, enabled = true) }

        PluginSourceType.entries.forEach { repository.setEnabled("a", it, enabled = false) }

        val stored = repository.getPlugin("a")!!
        assertFalse(stored.isEnabledAnywhere)
    }

    @Test
    fun `switching a source touches the update time`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "a", name = "Alpha", updatedAt = 1_000L))

        repository.setEnabled("a", PluginSourceType.METADATA, enabled = true)

        val stored = repository.getPlugin("a")!!
        assertTrue(stored.updatedAt > 1_000L, "a switch is a change to the row")
        assertTrue(stored.updatedAt <= System.currentTimeMillis())
    }

    @Test
    fun `reordering metadata numbers them from zero in the order given`() = runBlocking<Unit> {
        listOf("a", "b", "c").forEach { repository.upsertPlugin(plugin(id = it, name = it.uppercase())) }
        // The metadata orderings of the same three plugins are deliberately not all zero.
        repository.updateSortOrders(listOf("c", "b", "a"), PluginSourceType.LYRICS)

        repository.updateSortOrders(listOf("b", "c", "a"), PluginSourceType.METADATA)

        val stored = repository.getPlugins().associateBy { it.id }
        assertEquals(listOf("b", "c", "a"), stored.values.sortedBy { it.metadataSortOrder }.map { it.id })
        assertEquals(listOf(0, 1, 2), stored.values.sortedBy { it.metadataSortOrder }.map { it.metadataSortOrder })
        // The lyrics ordering is a separate column and must still be the one set above.
        assertEquals(listOf("c", "b", "a"), stored.values.sortedBy { it.lyricsSortOrder }.map { it.id })
    }

    @Test
    fun `reordering cover keeps the other two orderings`() = runBlocking<Unit> {
        listOf("a", "b").forEach { repository.upsertPlugin(plugin(id = it, name = it.uppercase())) }
        repository.updateSortOrders(listOf("b", "a"), PluginSourceType.METADATA)

        repository.updateSortOrders(listOf("a", "b"), PluginSourceType.COVER)

        val stored = repository.getPlugins().associateBy { it.id }
        assertEquals(listOf("b", "a"), stored.values.sortedBy { it.metadataSortOrder }.map { it.id })
        assertEquals(listOf("a", "b"), stored.values.sortedBy { it.coverSortOrder }.map { it.id })
    }

    @Test
    fun `a reordering of one plugin still numbers it zero`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "a", name = "Alpha").copy(metadataSortOrder = 5))

        repository.updateSortOrders(listOf("a"), PluginSourceType.METADATA)

        assertEquals(0, repository.getPlugin("a")!!.metadataSortOrder)
    }

    @Test
    fun `a custom name is trimmed and a blank one falls back to the plugin name`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "a", name = "Alpha"))

        repository.updateCustomName("a", "  My Source  ")
        assertEquals("My Source", repository.getPlugin("a")!!.displayName)

        repository.updateCustomName("a", "   ")
        val blanked = repository.getPlugin("a")!!
        assertNull(blanked.customName)
        assertEquals("Alpha", blanked.displayName)

        repository.updateCustomName("a", null)
        assertNull(repository.getPlugin("a")!!.customName)
    }

    @Test
    fun `the manifest contract of an installed plugin can be updated in place`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "a", name = "Alpha", apiVersion = 1, minHostApiVersion = 1))

        repository.updateManifestContract(
            id = "a",
            apiVersion = 3,
            minHostApiVersion = 2,
            capabilitiesJson = """["searchSongs","fetchLyrics"]""",
        )

        val stored = repository.getPlugin("a")!!
        assertEquals(3, stored.apiVersion)
        assertEquals(2, stored.minHostApiVersion)
        assertEquals("""["searchSongs","fetchLyrics"]""", stored.capabilitiesJson)
    }

    @Test
    fun `uninstalling removes the plugin and keeps the others`() = runBlocking<Unit> {
        repository.upsertPlugin(plugin(id = "a", name = "Alpha"))
        repository.upsertPlugin(plugin(id = "b", name = "Beta"))

        repository.uninstallPlugin("a")

        assertNull(repository.getPlugin("a"))
        assertEquals(listOf("b"), repository.getPlugins().map { it.id })
    }

    private fun plugin(
        id: String,
        name: String,
        versionName: String = "1.0.0",
        sortOrder: Int = 0,
        customName: String? = null,
        apiVersion: Int = 1,
        minHostApiVersion: Int = 1,
        updatedAt: Long = 1_000L,
    ) = SourcePluginEntity(
        id = id,
        name = name,
        versionCode = 1,
        versionName = versionName,
        author = "someone",
        description = "a source plugin",
        apiVersion = apiVersion,
        minHostApiVersion = minHostApiVersion,
        pluginDir = """H:\Lyrico\plugins\$id""",
        entryFile = "index.js",
        includeDirsJson = "[]",
        customName = customName,
        iconPath = null,
        enabled = true,
        sortOrder = sortOrder,
        installedAt = 500L,
        updatedAt = updatedAt,
    )
}
