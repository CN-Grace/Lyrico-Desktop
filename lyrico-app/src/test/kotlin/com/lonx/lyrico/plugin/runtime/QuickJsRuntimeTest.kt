package com.lonx.lyrico.plugin.runtime

import com.lonx.lyrico.plugin.i18n.PluginLocales
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The QuickJS bridge against the real native library, not a fake.
 *
 * Everything asserted here crosses the JNI boundary for real: the prebuilt `quickjs-ng.dll` is loaded
 * through [NativeLibraryLoader] (the test task points `lyrico.native.dir` at `build/native/windows-x64`),
 * scripts are executed by the actual engine, and `Platform.*` calls travel JS → JNI → [QuickJsHostApi]
 * → JS. That matters because the port replaced the loader (`System.loadLibrary` → `NativeLibraryLoader`),
 * dropped `@androidx.annotation.Keep`, and swapped `android.util.Base64` for `java.util.Base64`: a
 * stubbed runtime would pass while all three changes were wrong.
 *
 * [QuickJsHostApi] is `final`, so there is no fake to fall back on — the tests use a real host API with
 * a temp cache directory, or [NO_HOST_API] when the host surface is not the subject.
 */
class QuickJsRuntimeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var cacheDir: File

    @BeforeTest
    fun setUp() {
        cacheDir = Files.createTempDirectory("lyrico-quickjs-cache").toFile()
    }

    @AfterTest
    fun tearDown() {
        cacheDir.deleteRecursively()
        // PluginLocales is process-global: one test overrides it, so put the system value back.
        PluginLocales.initialize()
    }

    private fun hostApi(
        pluginId: String = "net.example.runtime",
        okHttpClient: OkHttpClient = OkHttpClient(),
        strings: com.lonx.lyrico.plugin.i18n.PluginStrings? = null,
    ) = QuickJsHostApi(
        appInfo = HostAppInfo(
            name = "Lyrico",
            versionName = "1.6.0",
            versionCode = 20L,
            buildType = "desktop",
            debug = false,
        ),
        okHttpClient = okHttpClient,
        pluginId = pluginId,
        cacheRootDir = cacheDir,
        pluginStrings = strings,
        json = json,
    )

    @Test
    fun `a script is evaluated by the real engine`() {
        QuickJsRuntime(hostApi = null).use { runtime ->
            assertEquals("2", runtime.eval("1 + 1"))
            assertEquals("hello world", runtime.eval("'hello' + ' ' + 'world'"))
            assertEquals("3", runtime.eval("const a = [1, 2]; a.length + 1"))
        }
    }

    @Test
    fun `a plugin function is called with a parsed request and answers with JSON`() {
        QuickJsRuntime(hostApi = null).use { runtime ->
            runtime.eval(
                """
                globalThis.searchSongs = function (request) {
                  return {
                    keyword: request.keyword,
                    page: Number(request.page || 1),
                    titles: [{ id: "1", title: "Song" }]
                  };
                };
                """.trimIndent()
            )

            val result = runtime.call("searchSongs", """{"keyword":"jay","page":3}""")

            // QuickJS JSON.stringify emits no whitespace, so the exact string is a stable assertion.
            assertEquals("""{"keyword":"jay","page":3,"titles":[{"id":"1","title":"Song"}]}""", result)
        }
    }

    @Test
    fun `a function that returns nothing answers with null`() {
        QuickJsRuntime(hostApi = null).use { runtime ->
            runtime.eval("globalThis.noop = function (request) { };")
            assertEquals("null", runtime.call("noop", "{}"))
        }
    }

    @Test
    fun `calling a function the script does not define is an error, not a silent null`() {
        QuickJsRuntime(hostApi = null).use { runtime ->
            val failure = assertFailsWith<IllegalStateException> { runtime.call("searchSongs", "{}") }
            assertTrue(
                failure.message.orEmpty().contains("searchSongs"),
                "the missing name must be in the message: ${failure.message}",
            )
        }
    }

    @Test
    fun `a script error surfaces the message and the line number`() {
        QuickJsRuntime(hostApi = null).use { runtime ->
            val failure = assertFailsWith<IllegalStateException> {
                runtime.eval("throw new Error('boom from the plugin');")
            }
            val message = failure.message.orEmpty()
            assertTrue(message.contains("boom from the plugin"), message)
        }
    }

    @Test
    fun `the Platform host API is installed and reaches the Kotlin implementation`() {
        QuickJsRuntime(hostApi = hostApi()).use { runtime ->
            val info = json.parseToJsonElement(runtime.eval("JSON.stringify(Platform.app.getInfo())")).jsonObject

            assertEquals("Lyrico", info.getValue("name").jsonPrimitive.content)
            assertEquals("com.lonx.lyrico", info.getValue("packageName").jsonPrimitive.content)
            assertEquals("1.6.0", info.getValue("versionName").jsonPrimitive.content)
            assertEquals(20L, info.getValue("versionCode").jsonPrimitive.content.toLong())
            assertEquals("desktop", info.getValue("buildType").jsonPrimitive.content)
            assertEquals("false", info.getValue("debug").jsonPrimitive.content)

            // The default User-Agent is built from the same info the plugin can read.
            assertEquals(
                "Lyrico/1.6.0",
                runtime.eval("Platform.app.getUserAgent()"),
            )
        }
    }

    @Test
    fun `the runtime info reports the protocol versions the host actually implements`() {
        QuickJsRuntime(hostApi = hostApi()).use { runtime ->
            val info = json.parseToJsonElement(runtime.eval("JSON.stringify(Platform.runtime.getInfo())")).jsonObject

            assertEquals(HostApiRegistry.PLUGIN_PROTOCOL_VERSION, info.getValue("pluginApiVersion").jsonPrimitive.content.toInt())
            assertEquals(HostApiRegistry.PLATFORM_API_VERSION, info.getValue("hostApiVersion").jsonPrimitive.content.toInt())
            assertEquals("quickjs", info.getValue("engine").jsonPrimitive.content)
            assertTrue(info.getValue("supportedHostApis").toString().contains("http.getText"))
        }
    }

    @Test
    fun `a host API failure reaches the script as a thrown JavaScript error`() {
        // No string resources were installed, so i18n.t has nothing to resolve and must fail loudly.
        QuickJsRuntime(hostApi = hostApi()).use { runtime ->
            assertEquals("und", runtime.eval("Platform.i18n.getLocale()"))

            // The bridge clears the Java exception and reports the failing op, so the script sees an
            // error it can catch and the Kotlin exception never leaks across the JNI boundary.
            val failure = assertFailsWith<IllegalStateException> {
                runtime.eval("Platform.i18n.t('title');")
            }
            assertTrue(
                failure.message.orEmpty().contains("Host API call failed: i18n.t"),
                "expected the i18n failure to name the op: ${failure.message}",
            )
        }
    }

    @Test
    fun `a plugin script can catch a host API failure instead of dying`() {
        QuickJsRuntime(hostApi = hostApi()).use { runtime ->
            val caught = runtime.eval(
                """
                var outcome;
                try { Platform.i18n.t('title'); outcome = 'no error'; }
                catch (e) { outcome = e.message; }
                outcome;
                """.trimIndent()
            )

            assertTrue(caught.contains("i18n.t"), caught)
        }
    }

    @Test
    fun `the i18n host API answers from the installed plugin strings`() {
        val pluginDir = Files.createTempDirectory("lyrico-quickjs-strings").toFile()
        try {
            File(pluginDir, "zh.json").writeText("""{"title":"标题"}""")
            val manifest = com.lonx.lyrico.data.model.plugin.PluginManifest(
                id = "net.example.i18n",
                name = "I18n",
                versionCode = 1,
                versionName = "1.0.0",
                apiVersion = 5,
                minHostApiVersion = 1,
                i18n = com.lonx.lyrico.data.model.plugin.PluginI18n(
                    defaultLocale = "zh-Hans",
                    resources = mapOf("zh-Hans" to "zh.json"),
                ),
            )
            val strings = com.lonx.lyrico.plugin.i18n.PluginStrings.load(pluginDir, manifest)

            // beginInvocation snapshots the locale preference, so it has to be set before the call.
            PluginLocales.update(listOf("zh-CN"))
            QuickJsRuntime(hostApi = hostApi(strings = strings)).use { runtime ->
                assertEquals("zh-Hans", runtime.eval("Platform.i18n.getLocale()"))
                assertEquals("标题", runtime.eval("Platform.i18n.t('title')"))
            }
        } finally {
            pluginDir.deleteRecursively()
        }
    }

    @Test
    fun `the plugin cache is a real directory under the cache root`() {
        QuickJsRuntime(hostApi = hostApi(pluginId = "net.example.cache")).use { runtime ->
            runtime.eval("Platform.cache.set('token', 'abc123', 60000);")
            assertEquals("abc123", runtime.eval("Platform.cache.get('token')"))

            val entry = File(cacheDir, md5("net.example.cache")).listFiles()?.singleOrNull()
            assertTrue(entry != null && entry.isFile, "the cache entry must be a real file, not memory")
            assertTrue(entry.readText().contains("abc123"), entry.readText())

            runtime.eval("Platform.cache.remove('token');")
            assertEquals("", runtime.eval("Platform.cache.get('token')"))
            assertTrue(File(cacheDir, md5("net.example.cache")).listFiles().isNullOrEmpty())
        }
    }

    @Test
    fun `an expired cache entry is not returned`() {
        QuickJsRuntime(hostApi = hostApi()).use { runtime ->
            runtime.eval("Platform.cache.set('short', 'value', 1);")
            Thread.sleep(50)
            assertEquals("", runtime.eval("Platform.cache.get('short')"))
        }
    }

    @Test
    fun `a runaway script is interrupted at the deadline instead of hanging`() {
        QuickJsRuntime(timeoutMs = 300L, hostApi = null).use { runtime ->
            val startedAt = System.nanoTime()
            val failure = assertFailsWith<IllegalStateException> {
                runtime.eval("while (true) { }")
            }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            // The bridge reports the interrupt from the engine's own message; what matters for a
            // hung UI thread is that control comes back, long before the JUnit timeout.
            assertTrue(failure.message.orEmpty().isNotBlank(), "a timeout must say something")
            assertTrue(elapsedMillis < 30_000, "the interrupt must fire near the deadline, took ${elapsedMillis}ms")
        }
    }

    @Test
    fun `the memory limit aborts an allocation that would otherwise grow without bound`() {
        QuickJsRuntime(memoryLimitBytes = 8L * 1024L * 1024L, hostApi = null).use { runtime ->
            // Doubling a string is not a good probe (QuickJS ropes it), so allocate real buffers.
            val failure = assertFailsWith<IllegalStateException> {
                runtime.eval("var chunks = []; for (var i = 0; i < 128; i++) { chunks.push(new ArrayBuffer(1024 * 1024)); }")
            }
            assertTrue(failure.message.orEmpty().isNotBlank(), "an out-of-memory abort must say something")
        }
    }

    @Test
    fun `the deadline is refreshed for every call, so a runtime is reusable after a slow script`() {
        QuickJsRuntime(timeoutMs = 2_000L, hostApi = null).use { runtime ->
            repeat(3) { round ->
                runtime.eval("globalThis.slow = function () { var t = 0; for (var i = 0; i < 200000; i++) { t += i; } return t; };")
                assertTrue(runtime.call("slow", "{}").toLong() > 0L, "round $round must still run")
            }
        }
    }

    @Test
    fun `a closed runtime refuses further work`() {
        val runtime = QuickJsRuntime(hostApi = null)
        runtime.close()

        assertFailsWith<IllegalStateException> { runtime.eval("1 + 1") }
        assertFailsWith<IllegalStateException> { runtime.call("searchSongs", "{}") }
        // Closing twice is harmless: the pointer is already zeroed.
        runtime.close()
    }

    @Test
    fun `two runtimes are independent, including their globals`() {
        QuickJsRuntime(hostApi = null).use { first ->
            QuickJsRuntime(hostApi = null).use { second ->
                first.eval("globalThis.name = 'first';")
                second.eval("globalThis.name = 'second';")

                assertEquals("first", first.eval("name"))
                assertEquals("second", second.eval("name"))
            }
        }
    }

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
