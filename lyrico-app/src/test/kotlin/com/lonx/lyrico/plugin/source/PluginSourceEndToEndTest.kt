package com.lonx.lyrico.plugin.source

import com.lonx.lyrico.data.model.lyrics.LyricsPayloadType
import com.lonx.lyrico.data.model.lyrics.SourceRuntimeConfig
import com.lonx.lyrico.data.model.plugin.PluginSourceType
import com.lonx.lyrico.data.support.LocalGitHubServer
import com.lonx.lyrico.plugin.i18n.PluginLocales
import com.lonx.lyrico.plugin.support.ManifestConfigField
import com.lonx.lyrico.plugin.support.PluginSandbox
import com.lonx.lyrico.plugin.support.pluginManifestJson
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole plugin path, once, with nothing faked.
 *
 * Every other plugin test isolates a stage: the installer gets a zip, the runtime gets a script, the
 * host API gets a call. This one runs the chain the app actually runs when a user hits search in a
 * plugin source — real zip → real install → real Room row → real `manifest.json` read → real
 * `includeDirs` concatenation → real QuickJS engine → real `Platform.http.getText` → real HTTP over
 * a loopback socket → real JSON parsing → `SongSearchResult`s — because the failures that only exist
 * *between* those stages (a script that was never concatenated, a config value that never crossed
 * the JNI boundary, an HTTP call that never left the machine) are the ones a stage-local test cannot
 * see.
 *
 * The plugin is written the way the docs tell authors to write one, down to a helper module in
 * `lib/01_http.js` reached through `includeDirs` and an API base URL read from the user's config.
 */
class PluginSourceEndToEndTest {

    private val sandbox = PluginSandbox()
    private val server = LocalGitHubServer().start()
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
        server.stop()
        sandbox.close()
        Locale.setDefault(originalDefaultLocale)
        PluginLocales.initialize()
    }

    @Test
    fun `an installed plugin searches a real HTTP API and returns parsed song results`() = runBlocking<Unit> {
        server.respond(
            "/search",
            """
            {"items":[
              {"id":101,"name":"山丘","artists":["李宗盛"],"album":{"name":"山丘"},
               "durationMs":319000,"cover":"http://127.0.0.1/cover/101.jpg"},
              {"id":102,"name":"","artists":["李宗盛"],"album":{"name":"山丘"}}
            ]}
            """.trimIndent(),
        )
        installPlugin()

        val source = manager.getSources(PluginSourceType.METADATA).single()
        assertEquals("net.example.weather", source.id)
        assertEquals("Weather Source", source.name)
        assertEquals(listOf("baseUrl"), source.configFields.map { it.key }, "the declared config field is parsed")
        source.applyConfig(SourceRuntimeConfig(values = mapOf("baseUrl" to "http://127.0.0.1:${server.port}")))

        val results = source.searchSongs(keyword = "山丘", page = 1, separator = "/", pageSize = 20)

        // 1. The archive's helper module was concatenated and the entry script ran.
        // 2. The config value the caller supplied reached the script and built the request URL.
        // 3. The request really went out over the loopback socket, with the plugin's own header and
        //    the platform's default User-Agent.
        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/search?q=%E5%B1%B1%E4%B8%98&page=1", request.path)
        assertEquals("example", request.header("X-Plugin"))
        assertContains(request.header("User-Agent").orEmpty(), "Lyrico")

        // 4. The response was parsed into the documented result shape.
        assertEquals(1, results.size, "the item without a name is not a song result")
        val song = results.single()
        assertEquals("101", song.id)
        assertEquals("山丘", song.title)
        assertEquals("李宗盛", song.artist)
        assertEquals("山丘", song.album)
        assertEquals(319_000L, song.duration)
        assertEquals("http://127.0.0.1/cover/101.jpg", song.picUrl)
        assertEquals("http://127.0.0.1/cover/101.jpg", song.fields["cover_url"])
        assertEquals("101", song.internal["example_id"])
        assertEquals("net.example.weather", song.pluginId)
        assertEquals("Weather Source", song.pluginName)

        // 5. And the call was reported to the app log with what it actually returned.
        val logged = sandbox.logs.entries.single { it.message == "Plugin song search returned 1 result(s)" }
        assertEquals("net.example.weather", logged.relatedId)
        assertContains(logged.detail.orEmpty(), "keyword=山丘")
        assertContains(logged.detail.orEmpty(), "resultCount=1")
    }

    @Test
    fun `paging asks the API for the page it was given`() = runBlocking<Unit> {
        server.respond("/search", """{"items":[]}""")
        installPlugin()
        val source = manager.getSources(PluginSourceType.METADATA).single()
        source.applyConfig(SourceRuntimeConfig(values = mapOf("baseUrl" to "http://127.0.0.1:${server.port}")))

        source.searchSongs(keyword = "q", page = 1, separator = "/", pageSize = 20)
        source.searchSongs(keyword = "q", page = 3, separator = "/", pageSize = 20)

        assertEquals(
            listOf("/search?q=q&page=1", "/search?q=q&page=3"),
            server.requests.map { it.path },
            "every page is a real request; the source is not paginating locally",
        )
        assertEquals(emptyList(), sandbox.logs.exceptions)
    }

    @Test
    fun `a failing API surfaces as a failed search, not as an empty result list`() = runBlocking<Unit> {
        server.respond("/search", "<html>upstream is down</html>", status = 502)
        installPlugin()
        val source = manager.getSources(PluginSourceType.METADATA).single()
        source.applyConfig(SourceRuntimeConfig(values = mapOf("baseUrl" to "http://127.0.0.1:${server.port}")))

        val failure = assertFailsWith<IllegalStateException> {
            source.searchSongs(keyword = "山丘", page = 1, separator = "/", pageSize = 20)
        }

        // The script's JSON.parse rejected the HTML body, and the engine reported it with the line.
        assertContains(failure.message.orEmpty(), "SyntaxError")
        assertTrue(server.requests.single().path.startsWith("/search"), "the call did go out")
        val recorded = sandbox.logs.exceptions.single()
        assertContains(recorded.message, "Plugin song search failed")
        assertContains(recorded.message, "plugin=net.example.weather")
        assertEquals("net.example.weather", recorded.relatedId)
    }

    @Test
    fun `a capability the plugin did not declare never reaches the script`() = runBlocking<Unit> {
        server.respond("/cover", """{"items":[{"id":1,"name":"cover"}]}""")
        installPlugin()
        val loggedBefore = sandbox.logs.entries.size
        val source = manager.getSources(PluginSourceType.METADATA).single()

        assertEquals(emptyList(), source.searchCovers("山丘", page = 1, pageSize = 5))
        assertEquals(emptyList(), server.requests, "nothing was fetched for an undeclared capability")
        assertEquals(loggedBefore, sandbox.logs.entries.size, "and nothing was reported as a call")
    }

    @Test
    fun `a lyrics capability returns candidate lyrics parsed from the API payload`() = runBlocking<Unit> {
        server.respond("/search", """{"items":[{"id":101,"name":"山丘","artists":["李宗盛"],"album":{"name":"山丘"}}]}""")
        server.respond(
            "/lyrics",
            """
            {"title":"山丘","artist":"李宗盛","album":"山丘","lrc":"[00:01.00]越过山丘\n[00:05.00]虽然已白了头"}
            """.trimIndent(),
        )
        installPlugin()
        val source = manager.getSources(PluginSourceType.LYRICS).single()
        source.applyConfig(SourceRuntimeConfig(values = mapOf("baseUrl" to "http://127.0.0.1:${server.port}")))

        val song = source.searchSongs(keyword = "山丘", page = 1, separator = "/", pageSize = 20).single()
        val candidates = source.getLyricsCandidates(song)

        assertEquals(2, server.requests.size, "the search and then the lyrics call")
        assertEquals(
            "/lyrics?title=%E5%B1%B1%E4%B8%98&artist=%E6%9D%8E%E5%AE%97%E7%9B%9B",
            server.requests.last().path,
        )
        val candidate = candidates.single()
        assertEquals("山丘", candidate.song.title)
        assertEquals("李宗盛", candidate.song.artist)
        assertEquals(LyricsPayloadType.RAW_PLAIN_LRC, candidate.lyrics.payloadType)
        assertEquals("[00:01.00]越过山丘\n[00:05.00]虽然已白了头", candidate.lyrics.rawPlainLrc)
        assertEquals("山丘", candidate.lyrics.tags["ti"])
        assertContains(
            sandbox.logs.entries.map { it.message },
            "Plugin lyrics call returned 1 candidate(s)",
        )
    }

    // --------------------------------------------------------------------- fixture

    /**
     * The plugin under test: `manifest.json`, an entry script, and a helper module under `lib/`.
     *
     * The module is where the URL building and the response mapping live, which is how the docs
     * suggest splitting a plugin — and it means the test only passes if `includeDirs` concatenation
     * really put the module in front of the entry script.
     */
    private fun installPlugin() = sandbox.install(
        manifestJson = pluginManifestJson(
            id = "net.example.weather",
            name = "Weather Source",
            includeDirs = listOf("lib"),
            capabilities = listOf("searchSongs", "getLyrics"),
            configFields = listOf(ManifestConfigField(key = "baseUrl", title = "API base URL")),
        ),
        script = """
            include("lib/01_http.js");

            globalThis.searchSongs = function (request) {
              var base = (request.config || {}).baseUrl || "";
              var body = Platform.http.getText(ExampleApi.searchUrl(base, request.keyword, request.page), {
                headers: { "X-Plugin": "example" }
              });
              var payload = JSON.parse(body);
              return (payload.items || []).map(function (item) {
                return ExampleApi.mapSong(item, request.separator);
              }).filter(function (song) { return song.id && song.title; });
            };

            globalThis.getLyrics = function (request) {
              var base = (request.config || {}).baseUrl || "";
              var song = request.song || {};
              var payload = JSON.parse(Platform.http.getText(ExampleApi.lyricsUrl(base, song), {
                headers: { "X-Plugin": "example" }
              }));
              return {
                type: "rawPlainLrc",
                tags: {
                  ti: payload.title,
                  ar: payload.artist,
                  al: payload.album,
                  date: "2013-08-01"
                },
                rawPlainLrc: payload.lrc
              };
            };
        """.trimIndent(),
        extra = listOf(
            "lib/01_http.js" to """
                globalThis.ExampleApi = {
                  searchUrl: function (base, keyword, page) {
                    return base + "/search?q=" + encodeURIComponent(keyword) + "&page=" + page;
                  },
                  lyricsUrl: function (base, song) {
                    return base + "/lyrics?title=" + encodeURIComponent(song.title || "") +
                           "&artist=" + encodeURIComponent(song.artist || "");
                  },
                  mapSong: function (item, separator) {
                    var artists = (item.artists || []).join(separator || "/");
                    var cover = item.cover || "";
                    return {
                      id: String(item.id || ""),
                      title: item.name || "",
                      artist: artists,
                      album: (item.album || {}).name || "",
                      duration: Number(item.durationMs || 0),
                      picUrl: cover,
                      fields: {
                        title: item.name || "",
                        artist: artists,
                        album: (item.album || {}).name || "",
                        cover_url: cover
                      },
                      internal: { example_id: String(item.id || "") }
                    };
                  }
                };
            """.trimIndent(),
        ),
        enabled = true,
    )
}
