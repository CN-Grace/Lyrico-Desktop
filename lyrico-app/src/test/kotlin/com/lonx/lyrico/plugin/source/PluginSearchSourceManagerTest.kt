package com.lonx.lyrico.plugin.source

import com.lonx.lyrico.data.model.entity.isEnabledAnywhere
import com.lonx.lyrico.data.model.plugin.PluginSourceType
import com.lonx.lyrico.plugin.i18n.PluginLocales
import com.lonx.lyrico.plugin.support.PluginSandbox
import com.lonx.lyrico.plugin.support.pluginManifestJson
import com.lonx.lyrico.plugin.support.sourceScript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The source layer's caching and filtering rules, exercised against real plugins.
 *
 * [PluginSearchSourceManager] is where installed plugins turn into [SearchSource]s that the search
 * screens can call, and almost everything it does is bookkeeping that only shows up in the wrong
 * circumstances: a stale cache after the user edits a plugin, a rebuilt cache after every
 * keystroke, a disabled plugin that keeps answering. Every test here installs a real plugin archive
 * and reads the source back, because the bookkeeping and the plugin's own behaviour are coupled —
 * the cache is only observable by asking the plugin what it returns.
 */
class PluginSearchSourceManagerTest {

    private val sandbox = PluginSandbox()
    private lateinit var manager: PluginSearchSourceManager
    private lateinit var originalDefaultLocale: Locale

    @BeforeTest
    fun setUp() {
        originalDefaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        PluginLocales.initialize()
        manager = PluginSearchSourceManager(
            repository = sandbox.repository,
            factory = ScriptSearchSourceFactory(json = sandbox.json, appLogRepository = sandbox.logs),
            installer = sandbox.installer,
            appLogRepository = sandbox.logs,
        )
    }

    @AfterTest
    fun tearDown() {
        manager.close()
        sandbox.close()
        Locale.setDefault(originalDefaultLocale)
        PluginLocales.initialize()
    }

    @Test
    fun `a plugin with every type switched off is not a source`() = runBlocking<Unit> {
        val plugin = sandbox.install(pluginManifestJson(name = "Example"), enabled = true)
        assertEquals(listOf("Example"), manager.getSources().map { it.name })

        PluginSourceType.entries.forEach { sourceType ->
            sandbox.repository.setEnabled(plugin.id, sourceType, false)
        }

        assertEquals(emptyList(), manager.getSources())
        assertEquals(emptyList(), manager.observeSources().first())
        // The row is still installed; only its usability changed.
        assertEquals(1, sandbox.repository.getPlugins().size)
        assertFalse(sandbox.repository.getPlugins().single().isEnabledAnywhere)
    }

    @Test
    fun `sources are filtered by capability and by per type enablement`() = runBlocking<Unit> {
        val metadataOnly = sandbox.install(
            pluginManifestJson(id = "net.example.metadata", name = "Metadata", capabilities = listOf("searchSongs")),
            enabled = true,
        )
        val lyrics = sandbox.install(
            pluginManifestJson(
                id = "net.example.lyrics",
                name = "Lyrics",
                capabilities = listOf("searchSongs", "getLyrics"),
            ),
            enabled = true,
        )

        assertEquals(
            setOf("Metadata", "Lyrics"),
            manager.getSources(PluginSourceType.METADATA).map { it.name }.toSet(),
        )
        assertEquals(listOf("Lyrics"), manager.getSources(PluginSourceType.LYRICS).map { it.name })
        assertEquals(emptyList(), manager.getSources(PluginSourceType.COVER))

        // A plugin that supports lyrics can still be switched off for lyrics alone.
        sandbox.repository.setEnabled(lyrics.id, PluginSourceType.LYRICS, false)
        assertEquals(emptyList(), manager.getSources(PluginSourceType.LYRICS))
        assertEquals(
            setOf("Metadata", "Lyrics"),
            manager.getSources(PluginSourceType.METADATA).map { it.name }.toSet(),
            "the metadata type is unaffected",
        )

        sandbox.repository.setEnabled(metadataOnly.id, PluginSourceType.METADATA, false)
        assertEquals(listOf("Lyrics"), manager.getSources(PluginSourceType.METADATA).map { it.name })
    }

    @Test
    fun `sources are ordered by the per type sort order and then by name`() = runBlocking<Unit> {
        val everyType = listOf("searchSongs", "getLyrics", "searchCovers")
        val b = sandbox.install(pluginManifestJson(id = "net.example.b", name = "Bravo", capabilities = everyType), enabled = true)
        val a = sandbox.install(pluginManifestJson(id = "net.example.a", name = "Alpha", capabilities = everyType), enabled = true)
        val c = sandbox.install(pluginManifestJson(id = "net.example.c", name = "Charlie", capabilities = everyType), enabled = true)

        // The initial order is the install order, which is what the user sees on a fresh library.
        assertEquals(listOf("Bravo", "Alpha", "Charlie"), manager.getSources(PluginSourceType.METADATA).map { it.name })

        sandbox.repository.updateSortOrders(listOf(c.id, b.id, a.id), PluginSourceType.METADATA)

        assertEquals(listOf("Charlie", "Bravo", "Alpha"), manager.getSources(PluginSourceType.METADATA).map { it.name })

        // A different type keeps its own order; the row order for metadata does not leak into it.
        sandbox.repository.updateSortOrders(listOf(a.id, c.id, b.id), PluginSourceType.LYRICS)
        assertEquals(listOf("Alpha", "Charlie", "Bravo"), manager.getSources(PluginSourceType.LYRICS).map { it.name })
        assertEquals(listOf("Charlie", "Bravo", "Alpha"), manager.getSources(PluginSourceType.METADATA).map { it.name })
    }

    @Test
    fun `getSourceWithState reports the enablement and answers null for an unknown plugin`() = runBlocking<Unit> {
        val plugin = sandbox.install(pluginManifestJson(name = "Example"))

        val disabled = manager.getSourceWithState(plugin.id)
        assertEquals("Example", disabled?.source?.name)
        assertEquals(false, disabled?.enabled)

        sandbox.repository.setEnabled(plugin.id, PluginSourceType.METADATA, true)
        assertEquals(true, manager.getSourceWithState(plugin.id)?.enabled)

        assertNull(manager.getSourceWithState("net.example.missing"))
    }

    @Test
    fun `a source is reused while the plugin row is unchanged and rebuilt when it changes`() = runBlocking<Unit> {
        val plugin = sandbox.install(pluginManifestJson(name = "Example"), enabled = true)

        val first = manager.getSources().single()
        assertSame(first, manager.getSources().single(), "reading twice must not rebuild the runtime")

        sandbox.repository.updateCustomName(plugin.id, "Renamed By The User")

        val renamed = manager.getSources().single()
        assertNotSame(first, renamed, "a changed row has to be rebuilt")
        assertEquals("Renamed By The User", renamed.name)
        assertEquals(first.id, renamed.id)
    }

    @Test
    fun `an edit on disk is invisible until the source is invalidated`() = runBlocking<Unit> {
        val plugin = sandbox.install(
            pluginManifestJson(name = "Example"),
            script = sourceScript("""[{"id":"1","title":"From v1"}]"""),
            enabled = true,
        )
        val source = manager.getSources().single()
        assertEquals(listOf("From v1"), source.searchSongs("k", page = 1, separator = "/", pageSize = 20).map { it.title })

        File(plugin.pluginDir, "source.js").writeText(sourceScript("""[{"id":"1","title":"From v2"}]"""))

        // Same row, so the cached runtime keeps answering: the plugin directory is not re-read.
        assertEquals(
            listOf("From v1"),
            manager.getSources().single().searchSongs("k", page = 1, separator = "/", pageSize = 20).map { it.title },
        )

        manager.invalidate(plugin.id)

        assertEquals(
            listOf("From v2"),
            manager.getSources().single().searchSongs("k", page = 1, separator = "/", pageSize = 20).map { it.title },
        )
    }

    @Test
    fun `a plugin whose entry file was removed is skipped and reported`() = runBlocking<Unit> {
        val good = sandbox.install(pluginManifestJson(id = "net.example.good", name = "Good"), enabled = true)
        val broken = sandbox.install(pluginManifestJson(id = "net.example.broken", name = "Broken"), enabled = true)
        File(broken.pluginDir, "source.js").delete()

        val sources = manager.getSources()

        assertEquals(listOf("Good"), sources.map { it.name })
        assertEquals(listOf("net.example.good"), sources.map { it.id })
        assertTrue(File(good.pluginDir, "source.js").isFile)
        val recorded = sandbox.logs.exceptions.single()
        assertEquals("Failed to build plugin search source\nplugin=net.example.broken\nname=Broken\nentry=source.js", recorded.message)
        assertTrue(recorded.throwable is java.io.FileNotFoundException, recorded.throwable.toString())
    }

    @Test
    fun `closing the manager releases the cached sources but leaves the plugins usable`() = runBlocking<Unit> {
        sandbox.install(pluginManifestJson(name = "Example"), enabled = true)
        val first = manager.getSources().single()

        manager.close()

        val rebuilt = manager.getSources().single()
        assertNotSame(first, rebuilt, "the cache was dropped")
        // The fresh runtime really runs: a closed one would fail here rather than answer.
        assertEquals(emptyList(), rebuilt.searchSongs("k", page = 1, separator = "/", pageSize = 20))
    }

    @Test
    fun `the locale in the plugin preferences decides which name a source reports`() = runBlocking<Unit> {
        sandbox.install(
            pluginManifestJson(
                name = "@title",
                minHostApiVersion = 4,
                i18nDefaultLocale = "en",
                resourceFiles = mapOf("en" to "en.json", "zh" to "zh.json"),
            ),
            extra = listOf(
                "en.json" to """{"title":"English Name"}""",
                "zh.json" to """{"title":"中文名"}""",
            ),
            enabled = true,
        )

        assertEquals(listOf("English Name"), manager.getSources().map { it.name })

        PluginLocales.update(Locale.SIMPLIFIED_CHINESE)

        assertEquals(listOf("中文名"), manager.getSources().map { it.name })
    }

    @Test
    fun `a locale change re-emits from the running flow, without resubscribing`() = runBlocking<Unit> {
        sandbox.install(
            pluginManifestJson(
                name = "@title",
                minHostApiVersion = 4,
                i18nDefaultLocale = "en",
                resourceFiles = mapOf("en" to "en.json", "zh" to "zh.json"),
            ),
            extra = listOf(
                "en.json" to """{"title":"English Name"}""",
                "zh.json" to """{"title":"中文名"}""",
            ),
            enabled = true,
        )

        val emissions = mutableListOf<List<String>>()
        val subscribed = CompletableDeferred<Unit>()
        val collector = launch {
            manager.observeSources().collect { sources ->
                emissions += sources.map { it.name }
                subscribed.complete(Unit)
            }
        }

        // Waiting for the first emission guarantees the collector is attached to both upstreams, so
        // the locale change below cannot race the subscription.
        subscribed.await()
        PluginLocales.update(Locale.SIMPLIFIED_CHINESE)
        withTimeout(5_000) {
            while (emissions.size < 2) delay(20)
        }
        collector.cancel()

        assertEquals(listOf("English Name"), emissions.first())
        assertEquals(listOf("中文名"), emissions.last())
    }

    @Test
    fun `an installed plugin that only declares searchSongs still answers a song search`() = runBlocking<Unit> {
        // The end-to-end contract in miniature: install → source → real QuickJS call → parsed results.
        sandbox.install(
            pluginManifestJson(id = "net.example.real", name = "Real"),
            script = sourceScript(
                """[
                    {"id":"7","title":"山丘","artist":"李宗盛","album":"山丘","fields":{"artist":"李宗盛"}},
                    {"title":"no id, dropped"}
                ]""",
            ),
            enabled = true,
        )

        val results = manager.getSources(PluginSourceType.METADATA).single()
            .searchSongs("山丘", page = 1, separator = "/", pageSize = 20)

        assertEquals(1, results.size, "a result without an id is not a song result")
        assertEquals("7", results.single().id)
        assertEquals("山丘", results.single().title)
        assertEquals("net.example.real", results.single().pluginId)
        val recorded = sandbox.logs.entries.single { it.message == "Plugin song search returned 1 result(s)" }
        assertContains(recorded.detail.orEmpty(), "resultCount=1")
        assertContains(recorded.detail.orEmpty(), "keyword=山丘")
    }
}
