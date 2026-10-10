package com.lonx.lyrico.plugin.support

import com.lonx.lyrico.data.model.entity.SourcePluginEntity
import com.lonx.lyrico.data.repository.SourcePluginRepository
import com.lonx.lyrico.data.repository.SourcePluginRepositoryImpl
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.plugin.source.SourcePluginInstaller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Everything a plugin needs in order to exist for real: a zip archive, an extraction directory, a
 * database row.
 *
 * The plugin tests deliberately do not mock the installer or the repository. A plugin is the one
 * part of the app whose input comes from outside the process, so the interesting failures live at
 * the seams between the archive, the filesystem and the database — exactly the seams a mocked
 * installer would erase. This harness therefore wires the real [SourcePluginInstaller] to a real
 * Room database and a real temp directory, and the archive builders below produce byte-level zips
 * (`ZipOutputStream`), not in-memory models.
 */
class PluginSandbox {

    private val workspace: File = Files.createTempDirectory("lyrico-plugin-sandbox").toFile()

    /** Where plugins are installed. Also where the installer stages a `.import-*` directory. */
    val installRoot: File = File(workspace, "install")

    val library = TestLibrary()
    val logs = RecordingAppLogRepository()
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }
    val repository: SourcePluginRepository = SourcePluginRepositoryImpl(dao = library.database.sourcePluginDao())
    val installer = SourcePluginInstaller(repository = repository, json = json, appLogRepository = logs)

    /**
     * Installs one plugin from a freshly built archive. The fixture is expected to be valid, so a
     * rejected archive fails the test here rather than surfacing as a confusing empty result later.
     */
    fun install(
        manifestJson: String = pluginManifestJson(),
        script: String = sourceScript("[]"),
        extra: List<Pair<String, String>> = emptyList(),
        enabled: Boolean = false,
    ): SourcePluginEntity = runBlocking {
        val session = installer.prepareImport(pluginArchive(manifestJson, script, extra), installRoot)
        check(session.failed.isEmpty()) { "fixture archive did not prepare: ${session.failed.map { it.reason }}" }
        val result = installer.installPrepared(session, enabled = enabled)
        check(result.failed.isEmpty()) { "fixture plugin did not install: ${result.failed.map { it.reason }}" }
        result.installed.single()
    }

    fun close() {
        library.close()
        workspace.deleteRecursively()
    }
}

/**
 * One configurable field in a manifest's `configFields`, in the shape the docs use.
 */
data class ManifestConfigField(
    val key: String,
    val title: String,
    val type: String = "text",
    val required: Boolean = true,
    val defaultValue: String = "",
)

/**
 * One plugin's `manifest.json`, matching what the docs tell plugin authors to write.
 *
 * [resourceFiles] maps a locale tag to the resource file shipped in the archive; when it is not
 * empty, [i18nDefaultLocale] must name one of the entries — the format the loader validates.
 */
fun pluginManifestJson(
    id: String = "net.example.source",
    name: String = "Example Source",
    versionCode: Int = 1,
    versionName: String = "1.0.0",
    apiVersion: Int = 5,
    minHostApiVersion: Int = 4,
    entry: String = "source.js",
    includeDirs: List<String> = emptyList(),
    icon: String? = null,
    capabilities: List<String> = emptyList(),
    configFields: List<ManifestConfigField> = emptyList(),
    i18nDefaultLocale: String? = null,
    resourceFiles: Map<String, String> = emptyMap(),
): String = buildJsonObject {
    put("id", id)
    put("name", name)
    put("versionCode", versionCode)
    put("versionName", versionName)
    put("apiVersion", apiVersion)
    put("minHostApiVersion", minHostApiVersion)
    put("entry", entry)
    if (includeDirs.isNotEmpty()) put("includeDirs", JsonArray(includeDirs.map { JsonPrimitive(it) }))
    if (icon != null) put("icon", icon)
    if (capabilities.isNotEmpty()) put("capabilities", JsonArray(capabilities.map { JsonPrimitive(it) }))
    if (configFields.isNotEmpty()) {
        put(
            "configFields",
            JsonArray(
                configFields.map { field ->
                    buildJsonObject {
                        put("key", field.key)
                        put("title", field.title)
                        put("type", field.type)
                        put("required", field.required)
                        put("defaultValue", field.defaultValue)
                    }
                },
            ),
        )
    }
    if (resourceFiles.isNotEmpty()) {
        put(
            "i18n",
            buildJsonObject {
                put("defaultLocale", i18nDefaultLocale ?: resourceFiles.keys.first())
                put(
                    "resources",
                    buildJsonObject { resourceFiles.forEach { (locale, file) -> put(locale, file) } },
                )
            },
        )
    }
}.toString()

/** A single-plugin archive: `manifest.json`, the entry script, then anything the case needs. */
fun pluginArchive(
    manifestJson: String = pluginManifestJson(),
    script: String = sourceScript("[]"),
    extra: List<Pair<String, String>> = emptyList(),
): InputStream = rawArchive(*(listOf("manifest.json" to manifestJson, "source.js" to script) + extra).toTypedArray())

/** A raw archive, keyed by zip entry name. Entry names are used verbatim, `../` and all. */
fun rawArchive(vararg entries: Pair<String, String>): InputStream {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
    return ByteArrayInputStream(output.toByteArray())
}

/**
 * A minimal, valid plugin script: `searchSongs` returns [body] as its result.
 *
 * [body] is a JavaScript expression — an array literal, normally. It has to be an *object*, not a
 * string: the JNI bridge answers a call with `JSON.stringify(result)`, so a function returning a
 * string would hand the parser a quoted JSON scalar instead of the payload it looks for.
 */
fun sourceScript(body: String): String = "globalThis.searchSongs = () => ($body);"
