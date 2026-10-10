package com.lonx.lyrico.plugin.runtime

import com.lonx.lyrico.data.support.LocalGitHubServer
import com.lonx.lyrico.plugin.i18n.PluginStrings
import com.lonx.lyrico.support.PlatformLogCapture
import com.lonx.lyrico.utils.logging.PlatformLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The `Platform.*` host API, called the way the JNI bridge calls it.
 *
 * The bridge resolves [QuickJsHostApi.call] by name, so calling it directly from Kotlin exercises the
 * same entry point a plugin's JavaScript reaches — without a script in the way. That keeps one test
 * per behaviour instead of one per script, and it means a failure here is unambiguously a host-API
 * failure rather than a bridge or JavaScript one.
 *
 * Two seams are real rather than faked: the cache writes into a temporary directory on the real
 * filesystem, and HTTP goes over a real socket to a loopback server. The loopback server matters
 * specifically for the `http.*` ops, because what a plugin depends on there is not just the response
 * body but the request that produced it — the default `User-Agent`, a caller-supplied header, the
 * JSON content type, the exact bytes of a POST body.
 */
class QuickJsHostApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private lateinit var cacheRoot: File
    private lateinit var server: LocalGitHubServer

    @BeforeTest
    fun setUp() {
        cacheRoot = Files.createTempDirectory("lyrico-host-api-cache").toFile()
        server = LocalGitHubServer().start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        cacheRoot.deleteRecursively()
    }

    // ---------------------------------------------------------------- base64

    @Test
    fun `base64 text survives a round trip including non ASCII`() {
        for (text in listOf("", "Lyrico", "音乐 · 歌词", "\uD83C\uDFB5", "line1\nline2")) {
            val encoded = callText("base64.encodeText", payload("text" to text))
            assertEquals(Base64.getEncoder().encodeToString(text.toByteArray()), encoded)
            assertEquals(text, callText("base64.decodeText", payload("base64" to encoded)))
        }
    }

    @Test
    fun `long base64 is produced on a single line and wrapped input is still decoded`() {
        // Android's NO_WRAP produced one unwrapped line, where java.util.Base64's basic encoder would
        // have inserted line separators; the DECODER, by contrast, is the tolerant MIME one.
        val text = "Lyrico".repeat(40)
        val encoded = callText("base64.encodeText", payload("text" to text))
        assertFalse(encoded.contains('\n'), "NO_WRAP must not wrap: $encoded")
        assertEquals(Base64.getEncoder().encodeToString(text.toByteArray()), encoded)

        val wrapped = encoded.chunked(76).joinToString("\n")
        assertTrue(wrapped.contains('\n'))
        assertEquals(text, callText("base64.decodeText", payload("base64" to wrapped)))
    }

    @Test
    fun `the URL safe alphabet is used without padding`() {
        // 0xFB 0xEF 0xBE is 111110 111110 111110 111110 → "++++" in the standard alphabet.
        val bytes = byteArrayOf(0xFB.toByte(), 0xEF.toByte(), 0xBE.toByte())
        val standard = Base64.getEncoder().encodeToString(bytes)
        assertEquals("++++", standard)

        assertEquals("----", callText("base64.encodeUrlBytes", payload("bytes" to bytes)))
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), callUrl(bytes))

        // Text goes through the same encoder, so the two ops agree.
        val text = "音乐歌词"
        assertEquals(
            callText("base64.toUrl", payload("base64" to callText("base64.encodeText", payload("text" to text)))),
            callText("base64.encodeUrlText", payload("text" to text)),
        )

        // Padding is stripped, and decoding puts it back.
        val padded = callText("base64.encodeUrlText", payload("text" to "a"))
        assertEquals("YQ", padded)
        assertEquals("a", callText("base64.decodeUrlText", payload("base64Url" to padded)))

        val urlBytes = callUrlToBytes(bytes)
        assertTrue(urlBytes.contentEquals(bytes), urlBytes.contentToString())
    }

    @Test
    fun `the toUrl and fromUrl transforms are pure string rewrites of standard base64`() {
        val bytes = byteArrayOf(0xFB.toByte(), 0xEF.toByte(), 0xBE.toByte(), 0x00)
        val standard = Base64.getEncoder().encodeToString(bytes) // "++++AA=="
        assertTrue(standard.endsWith("=="), standard)

        val url = callText("base64.toUrl", payload("base64" to standard))
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), url)

        // fromUrl only restores padding: the result is decodable standard base64 again.
        val restored = callText("base64.fromUrl", payload("base64Url" to url))
        assertEquals(standard, restored)
        assertTrue(callBytes("base64.decodeBytes", payload("base64" to restored)).contentEquals(bytes))
    }

    @Test
    fun `dropBytes removes a base64 encoded prefix`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = Base64.getEncoder().encodeToString(bytes)

        val dropped = callText("base64.dropBytes", payload("base64" to encoded, "count" to 2))
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(3, 4, 5)), dropped)

        // Dropping more than exists yields empty, not an error.
        assertEquals("", callText("base64.dropBytes", payload("base64" to encoded, "count" to 9)))
    }

    @Test
    fun `byte arrays cross the bridge as JSON arrays of unsigned values`() {
        val bytes = byteArrayOf(0, 127, 0x80.toByte(), 0xFF.toByte())
        val element = callJson("base64.decodeBytes", payload("base64" to Base64.getEncoder().encodeToString(bytes)))

        assertEquals(listOf(0, 127, 128, 255), element.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(
            Base64.getEncoder().encodeToString(bytes),
            callText("base64.encodeBytes", payload("bytes" to bytes)),
        )
    }

    // ----------------------------------------------------------------- bytes

    @Test
    fun `xor is cyclic over the key and the empty key is the identity`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val key = byteArrayOf(0x0F, 0xF0.toByte())

        // (1^0F, 2^F0, 3^0F, 4^F0, 5^0F)
        assertEquals(
            listOf(0x0E, 0xF2, 0x0C, 0xF4, 0x0A),
            callJson("bytes.xor", payload("bytes" to bytes, "key" to key)).jsonArray.map { it.jsonPrimitive.int },
        )
        assertTrue(callBytes("bytes.xor", payload("bytes" to bytes, "key" to byteArrayOf())).contentEquals(bytes))

        // xorBase64 is the same operation, Base64-encoded: a plugin sends payloads through it.
        val encoded = callText("bytes.xorBase64", payload("base64" to Base64.getEncoder().encodeToString(bytes), "key" to key))
        assertEquals(
            Base64.getEncoder().encodeToString(byteArrayOf(0x0E, 0xF2.toByte(), 0x0C, 0xF4.toByte(), 0x0A)),
            encoded,
        )
    }

    @Test
    fun `inflate reads a zlib stream produced by another encoder`() {
        val text = "{\"songs\":[\"音乐\"]}"
        val compressed = deflate(text)

        assertEquals(text, callText("compression.inflateBytesToText", payload("bytes" to compressed)))
        assertEquals(
            text,
            callText("compression.inflateBase64ToText", payload("base64" to Base64.getEncoder().encodeToString(compressed))),
        )
    }

    @Test
    fun `inflate on a corrupt payload stops at the truncated output instead of throwing`() {
        val compressed = deflate("0123456789")
        val corrupt = compressed.copyOfRange(0, compressed.size / 2)

        // Ported behaviour: the loop breaks when the inflater stops producing output. A plugin that
        // needs to detect corruption compares the result, it cannot rely on an exception.
        callText("compression.inflateBytesToText", payload("bytes" to corrupt))
    }

    // ---------------------------------------------------------------- crypto

    @Test
    fun `md5 matches the published vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", callText("crypto.md5", payload("text" to "")))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", callText("crypto.md5", payload("text" to "abc")))
        assertEquals("95521bb744463633c858cc50abb44e7d", callText("crypto.md5", payload("text" to "音乐")))
    }

    @Test
    fun `AES ECB PKCS5 encrypts and decrypts through the two documented forms`() {
        val key = "0123456789abcdef"
        val text = "phone=13800000000&password=secret"

        val base64 = callText("crypto.aesEcbPkcs5EncryptBase64", payload("text" to text, "key" to key))
        val hex = callText("crypto.aesEcbPkcs5EncryptHex", payload("text" to text, "key" to key))

        // Deterministic: one block cipher, no IV.
        assertEquals(base64, callText("crypto.aesEcbPkcs5EncryptBase64", payload("text" to text, "key" to key)))
        assertEquals(base64, Base64.getEncoder().encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))

        // PKCS5 padding: a whole extra block when the plaintext is a multiple of 16 bytes.
        val cipherBytes = Base64.getDecoder().decode(base64)
        assertEquals(0, cipherBytes.size % 16)
        assertTrue(cipherBytes.size > text.toByteArray().size)

        assertEquals(text, callText("crypto.aesEcbPkcs5DecryptBase64ToText", payload("base64" to base64, "key" to key)))
    }

    @Test
    fun `the AES key is used as given, so a 32 byte key is not truncated to 16`() {
        val text = "payload"
        val shortKey = "0123456789abcdef"
        val longKey = "0123456789abcdef0123456789abcdef"

        val short = callText("crypto.aesEcbPkcs5EncryptBase64", payload("text" to text, "key" to shortKey))
        val long = callText("crypto.aesEcbPkcs5EncryptBase64", payload("text" to text, "key" to longKey))
        assertNotEquals(short, long, "AES-256 must not silently degrade to AES-128")

        // An unusable key length fails loudly instead of being padded.
        assertFailsWith<Exception> {
            call("crypto.aesEcbPkcs5EncryptBase64", payload("text" to text, "key" to "short"))
        }
    }

    // ----------------------------------------------------------------- cache

    @Test
    fun `the cache stores values under a plugin scoped directory with an optional TTL`() {
        val api = newApi(pluginId = "net.example.cache")
        assertEquals("", callText("cache.get", payload("key" to "missing"), api = api))

        callText("cache.set", payload("key" to "song", "value" to "答案"), api = api)
        assertEquals("答案", callText("cache.get", payload("key" to "song"), api = api))

        // The on-disk layout is md5(pluginId)/md5(key).json, so a plugin's entries never collide.
        val pluginDir = File(cacheRoot, md5("net.example.cache"))
        assertEquals(listOf("${md5("song")}.json"), pluginDir.list()!!.sorted())
        assertContains(pluginDir.resolve("${md5("song")}.json").readText(), "答案")

        // A permanent entry survives an expiry sweep: ttlMs absent means "no expiry".
        Thread.sleep(5)
        assertEquals("答案", callText("cache.get", payload("key" to "song"), api = api))

        callText("cache.set", payload("key" to "short", "value" to "v", "ttlMs" to 40), api = api)
        assertEquals("v", callText("cache.get", payload("key" to "short"), api = api))
        Thread.sleep(80)
        assertEquals("", callText("cache.get", payload("key" to "short"), api = api))
        assertFalse(File(pluginDir, "${md5("short")}.json").exists(), "an expired entry is deleted on read")

        callText("cache.remove", payload("key" to "song"), api = api)
        assertEquals("", callText("cache.get", payload("key" to "song"), api = api))
    }

    @Test
    fun `two plugins keep separate caches and clear only their own`() {
        val first = newApi(pluginId = "net.example.one")
        val second = newApi(pluginId = "net.example.two")

        callText("cache.set", payload("key" to "k", "value" to "one"), api = first)
        callText("cache.set", payload("key" to "k", "value" to "two"), api = second)
        assertEquals("one", callText("cache.get", payload("key" to "k"), api = first))
        assertEquals("two", callText("cache.get", payload("key" to "k"), api = second))

        callText("cache.clear", api = first)
        assertEquals("", callText("cache.get", payload("key" to "k"), api = first))
        assertEquals("two", callText("cache.get", payload("key" to "k"), api = second))
    }

    @Test
    fun `a blank cache key is ignored rather than throwing`() {
        callText("cache.set", payload("key" to "   ", "value" to "value"))
        assertEquals("", callText("cache.get", payload("key" to "")))
        callText("cache.remove", payload("key" to " "))
    }

    @Test
    fun `a corrupt cache entry is discarded on read`() {
        val api = newApi(pluginId = "net.example.corrupt")
        callText("cache.set", payload("key" to "k", "value" to "v"), api = api)
        val file = File(File(cacheRoot, md5("net.example.corrupt")), "${md5("k")}.json")
        file.writeText("{not json")

        assertEquals("", callText("cache.get", payload("key" to "k"), api = api))
        assertFalse(file.exists())
    }

    // ------------------------------------------------------------------- log

    @Test
    fun `the log ops reach the platform log with the plugin's own tag`() {
        val capture = PlatformLogCapture().install()
        try {
            callText("log.debug", payload("tag" to "NetEase", "message" to "debug line"))
            callText("log.warn", payload("tag" to "NetEase", "message" to "warn line"))
            callText("log.error", payload("tag" to "NetEase", "message" to "error line"))

            val lines = capture.lines.filter { it.tag == "NetEase" }
            assertEquals(listOf(PlatformLog.DEBUG, PlatformLog.WARN, PlatformLog.ERROR), lines.map { it.level })
            assertEquals(listOf("debug line", "warn line", "error line"), lines.map { it.message })
        } finally {
            capture.restore()
        }
    }

    // ------------------------------------------------------------------ http

    @Test
    fun `getText sends the app user agent and returns the body`() {
        server.respond("/search", """{"songs":[]}""")

        val body = callText("http.getText", payload("url" to url("/search?keyword=%E9%9F%B3%E4%B9%90")))

        assertEquals("""{"songs":[]}""", body)
        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/search?keyword=%E9%9F%B3%E4%B9%90", request.path)
        assertEquals("Lyrico/1.6.0", request.header("User-Agent"))
    }

    @Test
    fun `a caller supplied header wins over the default and a blank one does not`() {
        server.respond("/headers", "ok")
        callText(
            "http.getText",
            payload(
                "url" to url("/headers"),
                "headers" to buildJsonObject {
                    put("User-Agent", "CustomAgent/9")
                    put("Referer", "https://example.test/")
                },
            ),
        )

        val request = server.requests.single()
        assertEquals("CustomAgent/9", request.header("User-Agent"))
        assertEquals("https://example.test/", request.header("Referer"))

        // A blank User-Agent is "not provided": the default is used instead of sending an empty one.
        callText(
            "http.getText",
            payload("url" to url("/headers"), "headers" to buildJsonObject { put("User-Agent", "  ") }),
        )
        assertEquals("Lyrico/1.6.0", server.requests.last().header("User-Agent"))
    }

    @Test
    fun `get returns the response envelope with headers and status`() {
        server.respond("/detail", """{"ok":true}""")

        val response = callJson("http.get", payload("url" to url("/detail"))).jsonObject

        assertEquals(200, response.getValue("code").jsonPrimitive.int)
        assertEquals("""{"ok":true}""", response.getValue("body").jsonPrimitive.content)
        val contentType = response.getValue("headers").jsonObject
            .entries.first { it.key.equals("Content-Type", ignoreCase = true) }
            .value.jsonArray.single().jsonPrimitive.content
        assertContains(contentType, "application/json")
    }

    @Test
    fun `postText sends the body with a JSON content type`() {
        server.respond("/submit", "accepted")

        val body = callText(
            "http.postText",
            payload("url" to url("/submit"), "body" to """{"keyword":"音乐"}"""),
        )

        assertEquals("accepted", body)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("""{"keyword":"音乐"}""", request.body.toString(Charsets.UTF_8))
        assertContains(request.header("Content-Type").orEmpty(), "application/json")
        assertEquals("Lyrico/1.6.0", request.header("User-Agent"))
    }

    @Test
    fun `postBytes and getBytes return opaque bytes as base64`() {
        val binary = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x0A)
        server.respondBytes("/binary", binary)
        server.respondBytes("/binary-post", binary.reversedArray())

        val get = callJson("http.getBytes", payload("url" to url("/binary"))).jsonObject
        assertEquals(200, get.getValue("code").jsonPrimitive.int)
        assertEquals(
            Base64.getEncoder().encodeToString(binary),
            get.getValue("bodyBase64").jsonPrimitive.content,
        )

        // The legacy `postBytes` form returns bare Base64 text instead of the envelope.
        val posted = callText(
            "http.postBytes",
            payload("url" to url("/binary-post"), "bodyBase64" to Base64.getEncoder().encodeToString(binary)),
        )
        assertEquals(Base64.getEncoder().encodeToString(binary.reversedArray()), posted)
        assertTrue(server.requests.last().body.contentEquals(binary))
    }

    @Test
    fun `an explicit body as byte values is accepted`() {
        server.respond("/bytes", "ok")

        callText(
            "http.postText",
            payload("url" to url("/bytes"), "bodyBytes" to byteArrayOf(0x41, 0x42)),
        )

        assertEquals("AB", server.requests.single().body.toString(Charsets.UTF_8))
    }

    @Test
    fun `a status other than 200 is returned rather than thrown`() {
        server.respond("/missing", """{"message":"Not Found"}""", status = 404)

        val response = callJson("http.get", payload("url" to url("/missing"))).jsonObject

        assertEquals(404, response.getValue("code").jsonPrimitive.int)
        assertEquals("""{"message":"Not Found"}""", response.getValue("body").jsonPrimitive.content)
    }

    @Test
    fun `a read timeout fails the call instead of hanging`() {
        server.respondSlowly("/slow", "too late", delayMillis = 3_000)

        val startedAt = System.currentTimeMillis()
        assertFailsWith<Exception> {
            call("http.getText", payload("url" to url("/slow"), "readTimeoutMs" to 150))
        }
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue(elapsed < 2_500, "the request should have been abandoned early, took ${elapsed}ms")
        assertEquals(1, server.requests.size, "the request did reach the server")
    }

    @Test
    fun `a blank url is rejected before any request is made`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            call("http.getText", payload("url" to "   "))
        }
        assertContains(failure.message.orEmpty(), "blank")
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a connection failure surfaces as an exception`() {
        // Bind and release a port so the address is certainly not listening.
        val deadPort = java.net.ServerSocket(0).use { it.localPort }

        assertFailsWith<Exception> {
            call("http.getText", payload("url" to "http://127.0.0.1:$deadPort/anything", "connectTimeoutMs" to 300))
        }
    }

    // ------------------------------------------------------------------- xml

    @Test
    fun `the xml ops dispatch to the XML host API`() {
        val xml = """<lyric><translation key="zh-Hans">旧</translation><empty/></lyric>"""

        val attributes = callJson(
            "xml.getRootAttributes",
            payload("xml" to """<lyric xmlns="u" xml:lang="zh"/>"""),
        ).jsonObject
        assertEquals("u", attributes.getValue("xmlns").jsonPrimitive.content)

        val found = callJson(
            "xml.findElements",
            payload("xml" to xml, "query" to buildJsonObject { put("tag", "translation") }),
        ).jsonArray
        assertEquals(1, found.size)
        assertEquals("旧", found.single().jsonObject.getValue("text").jsonPrimitive.content)

        val rewritten = callText(
            "xml.replaceChildrenByAttr",
            payload(
                "xml" to xml,
                "options" to buildJsonObject {
                    put("targetTag", "translation")
                    put("keyAttr", "key")
                    put("replacements", buildJsonObject {
                        put("zh-Hans", buildJsonObject { put("value", "新") })
                    })
                },
            ),
        )
        assertContains(rewritten, "新")

        val removed = callText(
            "xml.removeElements",
            payload("xml" to xml, "query" to buildJsonObject { put("tag", "empty") }),
        )
        assertFalse(removed.contains("<empty"))

        assertFailsWith<Exception> { call("xml.findElements", payload("xml" to "<broken")) }
    }

    @Test
    fun `an unknown host op names itself in the failure`() {
        val failure = assertFailsWith<IllegalStateException> { call("nope.nothing") }
        assertContains(failure.message.orEmpty(), "Unsupported host api: nope.nothing")
    }

    // ------------------------------------------------------------------ info

    @Test
    fun `app and runtime info describe the desktop host`() {
        val info = callJson("app.info").jsonObject
        assertEquals("Lyrico", info.getValue("name").jsonPrimitive.content)
        assertEquals("com.lonx.lyrico", info.getValue("packageName").jsonPrimitive.content)
        assertEquals("1.6.0", info.getValue("versionName").jsonPrimitive.content)
        assertEquals(20L, info.getValue("versionCode").jsonPrimitive.long)
        assertEquals("desktop", info.getValue("buildType").jsonPrimitive.content)
        assertEquals("Lyrico/1.6.0", callText("app.userAgent"))

        val runtime = callJson("runtime.info").jsonObject
        assertEquals("quickjs", runtime.getValue("engine").jsonPrimitive.content)
        assertTrue(runtime.getValue("pluginApiVersion").jsonPrimitive.int >= 5)
        assertTrue(runtime.getValue("supportedHostApis").jsonArray.isNotEmpty())
    }

    @Test
    fun `a plugin without string resources says so rather than returning a placeholder`() {
        val api = newApi(strings = null)

        assertEquals("und", callText("i18n.getLocale", api = api))
        val failure = assertFailsWith<IllegalArgumentException> { call("i18n.t", payload("key" to "title"), api = api) }
        assertContains(failure.message.orEmpty(), "no string resources")
    }

    // --------------------------------------------------------------- helpers

    private fun newApi(
        pluginId: String = "net.example.host",
        strings: PluginStrings? = null,
    ): QuickJsHostApi = QuickJsHostApi(
        appInfo = HostAppInfo(name = "Lyrico", versionName = "1.6.0", versionCode = 20L, buildType = "desktop"),
        okHttpClient = OkHttpClient(),
        pluginId = pluginId,
        cacheRootDir = cacheRoot,
        pluginStrings = strings,
        json = json,
    )

    private val api: QuickJsHostApi by lazy { newApi() }

    /** Settles the plugin's string snapshot first: that is what the bridge does before every call. */
    private fun call(name: String, payload: JsonObject = JsonObject(emptyMap()), api: QuickJsHostApi = this.api): String =
        api.also { it.beginInvocation() }.call(name, payload.toString())

    private fun callJson(name: String, payload: JsonObject = JsonObject(emptyMap()), api: QuickJsHostApi = this.api) =
        json.parseToJsonElement(call(name, payload, api)).jsonObject.getValue("value")

    private fun callText(name: String, payload: JsonObject = JsonObject(emptyMap()), api: QuickJsHostApi = this.api): String =
        callJson(name, payload, api).jsonPrimitive.content

    private fun callBytes(name: String, payload: JsonObject = JsonObject(emptyMap()), api: QuickJsHostApi = this.api): ByteArray =
        callBytes(name to payload, api = api)

    private fun callBytes(pair: Pair<String, JsonObject>, api: QuickJsHostApi = this.api): ByteArray =
        callJson(pair.first, pair.second, api).jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()

    private fun callUrl(bytes: ByteArray): String =
        callText("base64.encodeUrlBytes", payload("bytes" to bytes))

    private fun callUrlToBytes(bytes: ByteArray): ByteArray =
        callBytes("base64.decodeUrlBytes", payload("base64Url" to callUrl(bytes)))

    private fun payload(vararg entries: Pair<String, Any>): JsonObject = buildJsonObject {
        for ((key, value) in entries) {
            when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, value)
                is ByteArray -> put(key, JsonArray(value.map { JsonPrimitive(it.toInt() and 0xff) }))
                is JsonObject -> put(key, value)
                else -> throw IllegalArgumentException("unsupported payload value: $value")
            }
        }
    }

    private fun url(path: String): String = "http://127.0.0.1:${server.port}$path"

    /** A zlib stream (RFC 1950), which is what `Inflater()` — and therefore the host op — expects. */
    private fun deflate(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output, Deflater()).use { it.write(text.toByteArray()) }
        return output.toByteArray()
    }

    private fun md5(text: String): String = java.security.MessageDigest
        .getInstance("MD5")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
