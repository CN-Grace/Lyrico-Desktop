package com.lonx.lyrico.plugin.runtime

import com.lonx.lyrico.utils.logging.PlatformLog
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * The `Platform.*` host API a plugin script can call: i18n, app/runtime info, a file-backed cache,
 * crypto, base64, byte and compression helpers, HTTP, XML and logging.
 *
 * Instantiated once per plugin runtime and invoked from JavaScript through the JNI bridge, which
 * resolves [call] by name and signature `(String, String) -> String` — that signature is part of the
 * native contract and must not change.
 *
 * Ported from Android with three changes:
 *
 * - `android.util.Base64` → `java.util.Base64` (see [BASE64_ENCODER]/[BASE64_DECODER] for the flag
 *   mapping; the audit of every call site is recorded there).
 * - `android.util.Log` → [PlatformLog].
 * - `@androidx.annotation.Keep` dropped (no R8 on the desktop build; see [QuickJsNative]).
 */
class QuickJsHostApi(
    private val appInfo: HostAppInfo = HostAppInfo(),
    private val runtimeInfo: HostRuntimeInfo = HostRuntimeInfo(),
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val pluginId: String = "default",
    private val cacheRootDir: File? = null,
    private val pluginStrings: com.lonx.lyrico.plugin.i18n.PluginStrings? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
) {
    private var stringsSnapshot: com.lonx.lyrico.plugin.i18n.PluginStrings.Snapshot? = null

    fun beginInvocation() {
        stringsSnapshot = pluginStrings?.snapshot(com.lonx.lyrico.plugin.i18n.PluginLocales.preferences.value)
    }
    private companion object {
        const val CACHE_LOG_TAG = "PlatformPluginCache"

        /**
         * Equivalent of Android's `Base64.encodeToString(bytes, Base64.NO_WRAP)`: the JDK's basic
         * encoder emits no line breaks, so the output is byte-identical to the Android build.
         */
        private val BASE64_ENCODER: Base64.Encoder = Base64.getEncoder()

        /**
         * Equivalent of Android's `Base64.decode(text, Base64.DEFAULT)`, which every decode site in
         * this file used. Android's `DEFAULT` **decoder** tolerates line breaks, so the JDK's MIME
         * decoder (which ignores anything outside the base64 alphabet) is the matching replacement.
         *
         * The mirror-image trap is absent here: Android's `DEFAULT` **encoder** would wrap at 76
         * characters, but no encode site in this file passed `DEFAULT` — they all passed `NO_WRAP`.
         */
        private val BASE64_DECODER: Base64.Decoder = Base64.getMimeDecoder()

        /** Equivalent of `URL_SAFE or NO_WRAP or NO_PADDING`: URL-safe alphabet, no padding. */
        private val BASE64_URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }

    fun call(name: String, payloadJson: String): String {
        val payload = runCatching {
            json.parseToJsonElement(payloadJson).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))

        return when (name) {
            "i18n.getLocale" -> text(stringsSnapshot?.locale ?: "und")

            "i18n.t" -> text(requireNotNull(stringsSnapshot) { "Plugin has no string resources" }.format(
                payload.string("key"),
                (payload["args"] as? JsonArray).orEmpty().map { element ->
                    val primitive = element as? JsonPrimitive
                    if (primitive?.isString == true) primitive.content else primitive?.longOrNull
                }
            ))

            "app.info" -> value(appInfo.toJsonObject())

            "app.userAgent" -> text(buildDefaultUserAgent(appInfo))

            "runtime.info" -> value(runtimeInfo.toJsonObject())

            "cache.get" -> text(cacheGet(payload.string("key")))

            "cache.set" -> {
                cacheSet(
                    key = payload.string("key"),
                    value = payload.string("value"),
                    ttlMs = payload.longOrNull("ttlMs")
                )
                text("")
            }

            "cache.remove" -> {
                cacheRemove(payload.string("key"))
                text("")
            }

            "cache.clear" -> {
                cacheClear()
                text("")
            }

            "crypto.md5" -> text(md5(payload.string("text")))

            "crypto.aesEcbPkcs5EncryptBase64" -> text(
                aesEcbPkcs5EncryptBase64(
                    text = payload.string("text"),
                    key = payload.string("key")
                )
            )

            "crypto.aesEcbPkcs5EncryptHex" -> text(
                aesEcbPkcs5Encrypt(
                    text = payload.string("text"),
                    key = payload.string("key")
                ).toHex()
            )

            "crypto.aesEcbPkcs5DecryptBase64ToText" -> text(
                aesEcbPkcs5DecryptBase64ToText(
                    base64 = payload.string("base64"),
                    key = payload.string("key")
                )
            )

            "base64.encodeText" -> text(
                BASE64_ENCODER.encodeToString(payload.string("text").toByteArray(Charsets.UTF_8))
            )

            "base64.decodeText" -> text(
                String(
                    BASE64_DECODER.decode(payload.string("base64")),
                    Charsets.UTF_8
                )
            )

            "base64.dropBytes" -> text(
                BASE64_ENCODER.encodeToString(BASE64_DECODER.decode(payload.string("base64"))
                        .drop(payload.intOrNull("count") ?: 0)
                        .toByteArray())
            )

            "base64.decodeBytes" -> bytes(
                BASE64_DECODER.decode(payload.string("base64"))
            )

            "base64.encodeBytes" -> text(
                BASE64_ENCODER.encodeToString(payload.bytes("bytes"))
            )

            "base64.encodeUrlText" -> text(
                BASE64_URL_ENCODER.encodeToString(payload.string("text").toByteArray(Charsets.UTF_8))
            )

            "base64.decodeUrlText" -> text(
                String(
                    BASE64_DECODER.decode(fromBase64Url(payload.string("base64Url"))),
                    Charsets.UTF_8
                )
            )

            "base64.encodeUrlBytes" -> text(
                BASE64_URL_ENCODER.encodeToString(payload.bytes("bytes"))
            )

            "base64.decodeUrlBytes" -> bytes(
                BASE64_DECODER.decode(fromBase64Url(payload.string("base64Url")))
            )

            "base64.toUrl" -> text(
                toBase64Url(payload.string("base64"))
            )

            "base64.fromUrl" -> text(
                fromBase64Url(payload.string("base64Url"))
            )

            "bytes.xor" -> bytes(
                xor(
                    bytes = payload.bytes("bytes"),
                    key = payload.bytes("key")
                )
            )

            "bytes.xorBase64" -> text(
                BASE64_ENCODER.encodeToString(xor(
                        bytes = BASE64_DECODER.decode(payload.string("base64")),
                        key = payload.bytes("key")
                    ))
            )

            "compression.inflateBytesToText" -> text(
                inflate(payload.bytes("bytes"))
            )

            "compression.inflateBase64ToText" -> text(
                inflate(BASE64_DECODER.decode(payload.string("base64")))
            )

            /*
             * 旧 API：只返回 body。
             * 为了兼容现有 source.js / 01_http.js，不改变语义。
             */
            "http.getText" -> text(
                executeHttp(
                    method = "GET",
                    payload = payload,
                    binaryResponse = false
                ).bodyText
            )

            "http.postText" -> text(
                executeHttp(
                    method = "POST",
                    payload = payload,
                    binaryResponse = false
                ).bodyText
            )

            "http.postBytes" -> text(
                executeHttp(
                    method = "POST",
                    payload = payload,
                    binaryResponse = true
                ).bodyBase64
            )

            /*
             * 新 API：返回完整响应对象。
             *
             * http.get:
             * {
             *   code: 200,
             *   headers: { "Set-Cookie": ["..."] },
             *   body: "..."
             * }
             *
             * http.getBytes / http.postBytesResponse:
             * {
             *   code: 200,
             *   headers: { "Set-Cookie": ["..."] },
             *   bodyBase64: "..."
             * }
             */
            "http.get" -> value(
                executeHttp(
                    method = "GET",
                    payload = payload,
                    binaryResponse = false
                ).toJsonObject()
            )

            "http.post" -> value(
                executeHttp(
                    method = "POST",
                    payload = payload,
                    binaryResponse = false
                ).toJsonObject()
            )

            "http.getBytes" -> value(
                executeHttp(
                    method = "GET",
                    payload = payload,
                    binaryResponse = true
                ).toJsonObject()
            )

            "http.postBytesResponse" -> value(
                executeHttp(
                    method = "POST",
                    payload = payload,
                    binaryResponse = true
                ).toJsonObject()
            )
            "xml.getRootAttributes" -> value(
                HostXmlApi.getRootAttributes(
                    xml = payload.string("xml")
                )
            )

            "xml.findElements" -> value(
                HostXmlApi.findElements(
                    xml = payload.string("xml"),
                    query = payload.obj("query") ?: JsonObject(emptyMap()),
                    json = json
                )
            )

            "xml.replaceChildrenByAttr" -> text(
                HostXmlApi.replaceChildrenByAttr(
                    xml = payload.string("xml"),
                    options = payload.obj("options") ?: JsonObject(emptyMap())
                )
            )

            "xml.removeElements" -> text(
                HostXmlApi.removeElements(
                    xml = payload.string("xml"),
                    query = payload.obj("query") ?: JsonObject(emptyMap())
                )
            )
            "log.debug" -> {
                PlatformLog.d(payload.logTag(), payload.string("message"))
                text("")
            }

            "log.warn" -> {
                PlatformLog.w(payload.logTag(), payload.string("message"))
                text("")
            }

            "log.error" -> {
                PlatformLog.e(payload.logTag(), payload.string("message"))
                text("")
            }

            else -> error("Unsupported host api: $name")
        }
    }

    private fun cacheGet(key: String): String {
        val normalizedKey = key.trim()
        val file = cacheFile(normalizedKey) ?: run {
            logCache("get ignored blank key")
            return ""
        }
        if (!file.isFile) {
            logCache("miss plugin=$pluginId key=$normalizedKey")
            return ""
        }

        val entry = runCatching {
            json.parseToJsonElement(file.readText()).jsonObject
        }.getOrNull() ?: run {
            file.delete()
            logCache("corrupt entry removed plugin=$pluginId key=$normalizedKey")
            return ""
        }

        val expiresAt = entry.longOrNull("expiresAt") ?: 0L
        if (expiresAt > 0L && expiresAt <= System.currentTimeMillis()) {
            file.delete()
            logCache("expired entry removed plugin=$pluginId key=$normalizedKey")
            return ""
        }

        logCache(
            "hit plugin=$pluginId key=$normalizedKey expiresAt=$expiresAt valueLength=${entry.string("value").length}"
        )
        return entry.string("value")
    }

    private fun cacheSet(key: String, value: String, ttlMs: Long?) {
        val normalizedKey = key.trim()
        val file = cacheFile(normalizedKey) ?: run {
            logCache("set ignored blank key")
            return
        }
        val normalizedTtlMs = ttlMs ?: 0L
        val expiresAt = if (normalizedTtlMs > 0L) {
            System.currentTimeMillis() + normalizedTtlMs
        } else {
            0L
        }

        file.parentFile?.mkdirs()
        file.writeText(
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("value", value)
                    put("expiresAt", expiresAt)
                }
            )
        )
        logCache(
            "set plugin=$pluginId key=$normalizedKey ttlMs=$normalizedTtlMs expiresAt=$expiresAt valueLength=${value.length}"
        )
    }

    private fun cacheRemove(key: String) {
        val normalizedKey = key.trim()
        val removed = cacheFile(normalizedKey)?.delete() == true
        logCache("remove plugin=$pluginId key=$normalizedKey removed=$removed")
    }

    private fun cacheClear() {
        val cleared = pluginCacheDir()?.deleteRecursively() == true
        logCache("clear plugin=$pluginId cleared=$cleared")
    }

    private fun cacheFile(key: String): File? {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return null
        return File(pluginCacheDir() ?: return null, "${md5(normalizedKey)}.json")
    }

    private fun pluginCacheDir(): File? {
        val root = cacheRootDir ?: return null
        return File(root, md5(pluginId.ifBlank { "default" }))
    }

    private fun logCache(message: String) {
        PlatformLog.d(CACHE_LOG_TAG, message)
    }

    private fun executeHttp(
        method: String,
        payload: JsonObject,
        binaryResponse: Boolean
    ): HostHttpResponse {
        val urlText = payload.string("url")
        require(urlText.isNotBlank()) { "HTTP url is blank" }

        val contentType = payload.string("contentType").ifBlank {
            if (binaryResponse) {
                "application/octet-stream"
            } else {
                "application/json; charset=utf-8"
            }
        }

        val requestBuilder = Request.Builder().url(urlText)
        if (method == "POST" || method == "PUT" || method == "PATCH") {
            requestBuilder.header("Content-Type", contentType)
        }

        payload.obj("headers")?.forEach { (key, value) ->
            val headerValue = value.headerString()
            if (key.equals("User-Agent", ignoreCase = true) && headerValue.isBlank()) {
                return@forEach
            }
            requestBuilder.header(key, headerValue)
        }

        if (!hasNonBlankHeader(payload.obj("headers"), "User-Agent")) {
            requestBuilder.header("User-Agent", buildDefaultUserAgent(appInfo))
        }

        val requestBody = if (method == "POST" || method == "PUT" || method == "PATCH") {
            payload.requestBodyBytes().toRequestBody(contentType.toMediaType())
        } else {
            null
        }

        requestBuilder.method(method, requestBody)

        val client = okHttpClient.newBuilder()
            .followRedirects(payload.booleanOrNull("followRedirects") ?: true)
            .followSslRedirects(payload.booleanOrNull("followRedirects") ?: true)
            .connectTimeout((payload.intOrNull("connectTimeoutMs") ?: 8_000).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout((payload.intOrNull("readTimeoutMs") ?: 12_000).toLong(), TimeUnit.MILLISECONDS)
            .build()

        return client.newCall(requestBuilder.build()).execute().use { response ->
            val responseBytes = response.body.bytes()
            val bodyText = if (binaryResponse) {
                ""
            } else {
                responseBytes.toString(Charsets.UTF_8)
            }

            val bodyBase64 = if (binaryResponse) {
                BASE64_ENCODER.encodeToString(responseBytes)
            } else {
                ""
            }

            HostHttpResponse(
                code = response.code,
                message = response.message,
                headers = response.headers.toMultimap(),
                bodyText = bodyText,
                bodyBase64 = bodyBase64
            )
        }
    }

    private fun hasNonBlankHeader(headers: JsonObject?, targetName: String): Boolean {
        if (headers == null) return false
        return headers.any { (key, value) ->
            key.equals(targetName, ignoreCase = true) && value.headerString().isNotBlank()
        }
    }

    private fun JsonObject.requestBodyBytes(): ByteArray {
        val bodyBase64 = string("bodyBase64")
        if (bodyBase64.isNotBlank()) {
            return BASE64_DECODER.decode(bodyBase64)
        }

        val bodyBytes = this["bodyBytes"] as? JsonArray
        if (bodyBytes != null) {
            return ByteArray(bodyBytes.size) { index ->
                bodyBytes[index].jsonPrimitive.int.toByte()
            }
        }

        return string("body").toByteArray(Charsets.UTF_8)
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(
            text.toByteArray(Charsets.UTF_8)
        )
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun aesEcbPkcs5EncryptBase64(text: String, key: String): String {
        return BASE64_ENCODER.encodeToString(aesEcbPkcs5Encrypt(text, key))
    }

    private fun aesEcbPkcs5Encrypt(text: String, key: String): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    private fun aesEcbPkcs5DecryptBase64ToText(base64: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        return String(
            cipher.doFinal(BASE64_DECODER.decode(base64)),
            Charsets.UTF_8
        )
    }

    private fun toBase64Url(base64: String): String {
        return base64
            .trim()
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    }

    private fun fromBase64Url(base64Url: String): String {
        val normalized = base64Url
            .trim()
            .replace('-', '+')
            .replace('_', '/')

        val padding = when (normalized.length % 4) {
            0 -> ""
            2 -> "=="
            3 -> "="
            else -> ""
        }

        return normalized + padding
    }

    private fun xor(bytes: ByteArray, key: ByteArray): ByteArray {
        if (key.isEmpty()) return bytes
        return ByteArray(bytes.size) { index ->
            (bytes[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
    }

    private fun inflate(bytes: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(bytes)

        val buffer = ByteArray(4096)
        val output = ByteArrayOutputStream()

        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                output.write(buffer, 0, count)
            }
        } finally {
            inflater.end()
        }

        return output.toString("UTF-8")
    }

    private fun text(value: String): String {
        return value(JsonPrimitive(value))
    }

    private fun bytes(value: ByteArray): String {
        return value(
            JsonArray(
                value.map { byte ->
                    JsonPrimitive(byte.toInt() and 0xff)
                }
            )
        )
    }

    private fun value(value: JsonElement): String {
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("value", value)
            }
        )
    }

    private data class HostHttpResponse(
        val code: Int,
        val message: String,
        val headers: Map<String, List<String>>,
        val bodyText: String,
        val bodyBase64: String
    ) {
        fun toJsonObject(): JsonObject {
            return buildJsonObject {
                put("code", code)
                put("message", message)

                put(
                    "headers",
                    JsonObject(
                        headers.mapValues { (_, values) ->
                            JsonArray(values.map { JsonPrimitive(it) })
                        }
                    )
                )

                if (bodyText.isNotEmpty()) {
                    put("body", bodyText)
                } else {
                    put("body", "")
                }

                if (bodyBase64.isNotEmpty()) {
                    put("bodyBase64", bodyBase64)
                } else {
                    put("bodyBase64", "")
                }
            }
        }
    }
}

data class HostAppInfo(
    val name: String = "Lyrico",
    val packageName: String = DEFAULT_PACKAGE_NAME,
    val versionName: String = "0.0.0",
    val versionCode: Long = 0,
    val buildType: String = "unknown",
    val debug: Boolean = false
) {

    companion object {
        /**
         * The Android application id, kept unchanged on desktop: it is what an installed plugin saw
         * in `app.info`, so a plugin branching on it keeps working after the port. (The `app.userAgent`
         * string does not include it — see [buildDefaultUserAgent].)
         */
        const val DEFAULT_PACKAGE_NAME: String = "com.lonx.lyrico"
    }

    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("name", name)
            put("packageName", packageName)
            put("versionName", versionName)
            put("versionCode", versionCode)
            put("buildType", buildType)
            put("debug", debug)
        }
    }
}

data class HostRuntimeInfo(
    val pluginApiVersion: Int = HostApiRegistry.PLUGIN_PROTOCOL_VERSION,
    val hostApiVersion: Int = HostApiRegistry.PLATFORM_API_VERSION,
    val engine: String = "quickjs",
    val engineVersion: String? = null,
    val supportedHostApis: Set<String> = HostApiRegistry.SUPPORTED_HOST_APIS
) {
    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("pluginApiVersion", pluginApiVersion)
            put("hostApiVersion", hostApiVersion)
            put("engine", engine)
            if (engineVersion != null) {
                put("engineVersion", engineVersion)
            } else {
                put("engineVersion", JsonNull)
            }
            put(
                "supportedHostApis",
                JsonArray(supportedHostApis.sorted().map { JsonPrimitive(it) })
            )
        }
    }
}

fun buildDefaultUserAgent(appInfo: HostAppInfo): String {
    return "${appInfo.name}/${appInfo.versionName}"
}

private fun JsonObject.string(key: String): String {
    return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}

private fun JsonObject.intOrNull(key: String): Int? {
    return this[key]?.jsonPrimitive?.int
}

private fun JsonObject.longOrNull(key: String): Long? {
    return this[key]?.jsonPrimitive?.longOrNull
}

private fun JsonObject.booleanOrNull(key: String): Boolean? {
    return this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}

private fun JsonObject.obj(key: String): JsonObject? {
    return this[key] as? JsonObject
}

private fun JsonObject.bytes(key: String): ByteArray {
    val array = this[key]?.jsonArray ?: return ByteArray(0)
    return ByteArray(array.size) { index ->
        array[index].jsonPrimitive.int.toByte()
    }
}

private fun JsonElement.headerString(): String {
    return when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> joinToString(", ") {
            it.jsonPrimitive.contentOrNull.orEmpty()
        }
        else -> toString()
    }
}

private fun ByteArray.toHex(): String {
    return joinToString("") { "%02X".format(it) }
}

private fun JsonObject.logTag(): String {
    return string("tag")
        .ifBlank { "PlatformPlugin" }
        .take(48)
}
