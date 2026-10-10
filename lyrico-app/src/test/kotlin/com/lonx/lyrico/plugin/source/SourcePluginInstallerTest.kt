package com.lonx.lyrico.plugin.source

import com.lonx.lyrico.data.model.plugin.PluginCapability
import com.lonx.lyrico.data.repository.SourcePluginRepository
import com.lonx.lyrico.data.repository.SourcePluginRepositoryImpl
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.support.PlatformLogCapture
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plugin import and installation, against real archives, a real filesystem and a real database.
 *
 * This is the app's untrusted-input boundary: a plugin archive is a zip a user downloaded, and the
 * installer is the only thing standing between it and the user's disk. So the tests build actual zips
 * — including the ones an attacker would build, like `../escape.js` — and assert against what ended
 * up on the filesystem and in the database rather than against what a mock was told.
 *
 * Four behaviours are worth calling out because they are easy to get wrong and invisible when wrong:
 *
 * - **A rejected archive leaves nothing behind.** Preparation extracts into a `.import-*` directory
 *   inside the install root, and a failure there has to take that directory with it or repeated
 *   attempts accumulate junk (and, for a zip-slip attempt, half-written files).
 * - **One bad plugin does not sink the archive.** Validation is per candidate, so a malformed
 *   `manifest.json` in one folder still lets the other plugin in the same zip install.
 * - **An upgrade keeps the user's choices.** Re-installing must not reset `enabled`, `sortOrder`,
 *   `customName` or `installedAt` — those belong to the user, not to the archive.
 * - **Only the plugin root is copied.** Two plugins in one archive cannot swallow each other's files.
 */
class SourcePluginInstallerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private lateinit var library: TestLibrary
    private lateinit var repository: SourcePluginRepository
    private lateinit var logs: RecordingAppLogRepository
    private lateinit var workspace: File
    private lateinit var installRoot: File

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        repository = SourcePluginRepositoryImpl(dao = library.database.sourcePluginDao())
        logs = RecordingAppLogRepository()
        workspace = Files.createTempDirectory("lyrico-plugin-install").toFile()
        installRoot = File(workspace, "install")
    }

    @AfterTest
    fun tearDown() {
        library.close()
        workspace.deleteRecursively()
    }

    // -------------------------------------------------------------- happy paths

    @Test
    fun `an archive with a manifest installs its files and records the plugin`() = runBlocking<Unit> {
        val session = installer().prepareImport(
            archive(
                "manifest.json" to manifest(name = "Example Source", capabilities = listOf("searchSongs", "getLyrics")),
                "source.js" to "globalThis.searchSongs = () => [];",
                "lib/util.js" to "globalThis.helper = 1;",
            ),
            installRoot,
        )

        assertTrue(session.failed.isEmpty(), session.failed.toString())
        val candidate = session.candidates.single()
        assertEquals("net.example.source", candidate.manifest.id)
        assertEquals(".", candidate.relativeRootInArchive)
        assertEquals(PluginVersionConflict.NONE, candidate.versionConflict)
        assertNull(candidate.existingPlugin)
        assertTrue(File(session.tempDir, "manifest.json").isFile, "preparation extracts into a temp dir")
        assertEquals(installRoot.canonicalFile, session.tempDir.parentFile?.canonicalFile)

        val result = installer().installPrepared(session)

        assertTrue(result.failed.isEmpty(), result.failed.toString())
        val installed = result.installed.single()
        assertEquals("net.example.source", installed.id)
        assertEquals("Example Source", installed.name)
        assertEquals(File(installRoot, "net.example.source").absolutePath, installed.pluginDir)
        assertEquals("source.js", installed.entryFile)
        assertEquals(
            listOf("getLyrics", "searchSongs"),
            json.decodeFromString<List<String>>(installed.capabilitiesJson).sorted(),
        )
        assertFalse(installed.enabled, "a fresh install is disabled until the user enables it")

        // The files are really on disk.
        assertEquals("globalThis.searchSongs = () => [];", File(installed.pluginDir, "source.js").readText())
        assertEquals("globalThis.helper = 1;", File(installed.pluginDir, "lib/util.js").readText())
        assertFalse(session.tempDir.exists(), "installation discards the preparation directory")

        // And the row reads back through the repository, so it is really in the database.
        assertEquals(installed, repository.getPlugin("net.example.source"))
    }

    @Test
    fun `a plugin nested in a folder installs under its own id`() = runBlocking<Unit> {
        val session = installer().prepareImport(
            archive(
                "MyPlugin/manifest.json" to manifest(name = "Nested"),
                "MyPlugin/source.js" to sourceJs("x"),
                "MyPlugin/readme.md" to "docs",
            ),
            installRoot,
        )

        assertEquals("MyPlugin", session.candidates.single().relativeRootInArchive)

        val installed = install(session).installed.single()

        assertEquals(File(installRoot, "net.example.source").absolutePath, installed.pluginDir)
        assertTrue(File(installed.pluginDir, "manifest.json").isFile)
        assertTrue(File(installed.pluginDir, "source.js").isFile)
        assertFalse(File(installed.pluginDir, "MyPlugin").exists(), "the wrapper folder is not copied")
    }

    @Test
    fun `a nested plugin does not swallow a sibling plugin's files`() = runBlocking<Unit> {
        // The outer plugin's folder contains the inner plugin's folder: copying the outer one has to
        // stop at the inner plugin's root, or the inner plugin's files land in both directories.
        val session = installer().prepareImport(
            archive(
                "outer/manifest.json" to manifest(id = "net.example.outer", name = "Outer"),
                "outer/source.js" to "outer",
                "outer/inner/manifest.json" to manifest(id = "net.example.inner", name = "Inner"),
                "outer/inner/source.js" to "inner",
            ),
            installRoot,
        )

        assertEquals(
            listOf("net.example.inner", "net.example.outer"),
            session.candidates.map { it.manifest.id }.sorted(),
        )

        val installed = install(session).installed.associateBy { it.id }

        assertFalse(File(installed.getValue("net.example.outer").pluginDir, "inner").exists())
        assertEquals("inner", File(installed.getValue("net.example.inner").pluginDir, "source.js").readText())
    }

    @Test
    fun `only the selected plugins are installed from a multi plugin archive`() = runBlocking<Unit> {
        val session = installer().prepareImport(
            archive(
                "a/manifest.json" to manifest(id = "net.example.a", name = "A"),
                "a/source.js" to "a",
                "b/manifest.json" to manifest(id = "net.example.b", name = "B"),
                "b/source.js" to "b",
            ),
            installRoot,
        )
        assertEquals(2, session.candidates.size)

        val result = installer().installPrepared(session, selectedRoots = setOf("a"))

        assertEquals(listOf("net.example.a"), result.installed.map { it.id })
        assertNull(repository.getPlugin("net.example.b"))
        assertFalse(File(installRoot, "net.example.b").exists())
    }

    // ------------------------------------------------------------ whole archive

    @Test
    fun `an archive without a manifest fails as a whole`() = runBlocking<Unit> {
        val session = installer().prepareImport(archive("source.js" to "x"), installRoot)

        assertTrue(session.candidates.isEmpty())
        val failure = session.failed.single()
        assertEquals("Plugin manifest not found", failure.reason)
        assertEquals(".", failure.rootPath)
        assertEquals(".", failure.displayName)
        assertFalse(failure.hasPluginInfo)
    }

    @Test
    fun `one broken plugin does not stop a good one in the same archive`() = runBlocking<Unit> {
        val session = installer().prepareImport(
            archive(
                "good/manifest.json" to manifest(id = "net.example.good", name = "Good"),
                "good/source.js" to sourceJs("good"),
                "bad/manifest.json" to "{ this is not json",
                "bad/source.js" to "bad",
            ),
            installRoot,
        )

        assertEquals(listOf("net.example.good"), session.candidates.map { it.manifest.id })
        val failure = session.failed.single()
        assertContains(failure.reason, "manifest parse failed")
        assertContains(failure.rootPath, "bad")
        assertNull(failure.pluginId, "a manifest that cannot be parsed has no id to report")

        val result = installer().installPrepared(session)

        assertEquals(listOf("net.example.good"), result.installed.map { it.id })
        assertEquals(1, result.failed.size, "the import-level failure survives into the install result")
        assertEquals(sourceJs("good"), File(installRoot, "net.example.good/source.js").readText())
    }

    @Test
    fun `two plugins sharing an id are both rejected`() = runBlocking<Unit> {
        val session = installer().prepareImport(
            archive(
                "first/manifest.json" to manifest(name = "First"),
                "first/source.js" to "first",
                "second/manifest.json" to manifest(name = "Second"),
                "second/source.js" to "second",
            ),
            installRoot,
        )

        assertTrue(session.candidates.isEmpty())
        assertEquals(2, session.failed.size)
        for (failure in session.failed) {
            assertContains(failure.reason, "Duplicate plugin id in archive: net.example.source")
        }
        assertEquals(setOf("first", "second"), session.failed.map { it.rootPath }.toSet())
        assertTrue(installer().installPrepared(session).installed.isEmpty())
    }

    // ---------------------------------------------------------------- zip slip

    @Test
    fun `a zip entry that escapes the install directory is rejected before anything is written`() = runBlocking<Unit> {
        val failure = assertFailsWith<IllegalArgumentException> {
            installer().prepareImport(
                archive(
                    "manifest.json" to manifest(),
                    "source.js" to "x",
                    "../escape.js" to "pwned",
                ),
                installRoot,
            )
        }

        assertContains(failure.message.orEmpty(), "Unsafe zip entry: ../escape.js")
        assertFalse(File(workspace, "escape.js").exists(), "nothing may be written outside the install root")
        assertEquals(
            emptyList(),
            installRoot.listFiles()?.toList().orEmpty().map { it.name },
            "the preparation directory is cleaned up",
        )
    }

    @Test
    fun `absolute and backslash zip entries are rejected`() = runBlocking<Unit> {
        val absolute = assertFailsWith<IllegalArgumentException> {
            installer().prepareImport(archive("/abs.js" to "x"), installRoot)
        }
        assertContains(absolute.message.orEmpty(), "Absolute zip entry is not allowed: /abs.js")

        val backslash = assertFailsWith<IllegalArgumentException> {
            installer().prepareImport(archive("dir\\evil.js" to "x"), installRoot)
        }
        assertContains(backslash.message.orEmpty(), "Backslash zip entry is not allowed")
    }

    @Test
    fun `the plugin paths inside a manifest are also confined to the plugin root`() = runBlocking<Unit> {
        fun reasonFor(manifestJson: String): String = runBlocking {
            installer().prepareImport(pluginArchive(manifestJson = manifestJson), installRoot).failed.single().reason
        }

        assertContains(reasonFor(manifest(entry = "../escape.js")), "Unsafe entry path: ../escape.js")
        assertContains(reasonFor(manifest(icon = "../icon.png")), "Unsafe icon path: ../icon.png")
        assertContains(reasonFor(manifest(includeDirs = listOf("../outside"))), "Unsafe includeDir: ../outside")
    }

    // ------------------------------------------------------------ version rules

    @Test
    fun `a newer version updates the plugin and keeps the user's choices`() = runBlocking<Unit> {
        install(installer().prepareImport(pluginArchive(script = sourceJs("v1")), installRoot), enabled = true)
        assertTrue(repository.getPlugin("net.example.source")!!.enabled)
        repository.updateCustomName("net.example.source", "My Source")
        val renamed = repository.getPlugin("net.example.source")!!

        val session = installer().prepareImport(
            pluginArchive(
                manifestJson = manifest(name = "Renamed Upstream", versionCode = 2, versionName = "2.0.0"),
                script = sourceJs("v2"),
            ),
            installRoot,
        )
        assertEquals(PluginVersionConflict.UPDATE, session.candidates.single().versionConflict)
        assertEquals("1.0.0", session.candidates.single().existingPlugin?.versionName)

        val installed = install(session).installed.single()

        assertEquals("2.0.0", installed.versionName)
        assertEquals("Renamed Upstream", installed.name)
        assertEquals(renamed.installedAt, installed.installedAt, "an update is not a fresh install")
        assertEquals(renamed.sortOrder, installed.sortOrder)
        assertEquals("My Source", installed.customName, "the user's name for the plugin survives")
        assertTrue(installed.enabled)
        assertEquals(sourceJs("v2"), File(installed.pluginDir, "source.js").readText())
        assertEquals(1, repository.getPlugins().size, "an update does not add a second row")
    }

    @Test
    fun `the same version overwrites and an older one needs explicit consent`() = runBlocking<Unit> {
        install(
            installer().prepareImport(
                pluginArchive(manifestJson = manifest(versionCode = 3, versionName = "3.0.0"), script = sourceJs("v3")),
                installRoot,
            ),
        )

        val same = installer().prepareImport(
            pluginArchive(manifestJson = manifest(versionCode = 3, versionName = "3.0.0"), script = sourceJs("v3-again")),
            installRoot,
        )
        assertEquals(PluginVersionConflict.OVERWRITE, same.candidates.single().versionConflict)
        val overwritten = install(same).installed.single()
        assertEquals(sourceJs("v3-again"), File(overwritten.pluginDir, "source.js").readText())

        val olderSession = installer().prepareImport(
            pluginArchive(manifestJson = manifest(versionCode = 2, versionName = "2.0.0"), script = sourceJs("v2")),
            installRoot,
        )
        assertEquals(PluginVersionConflict.DOWNGRADE, olderSession.candidates.single().versionConflict)

        val refused = installer().installPrepared(olderSession)

        assertTrue(refused.installed.isEmpty())
        val failure = refused.failed.single()
        assertEquals("Import version is lower than installed version: net.example.source", failure.reason)
        assertEquals(3, failure.existingVersionCode)
        assertEquals("3.0.0", failure.existingVersionName)
        assertEquals(PluginVersionConflict.DOWNGRADE, failure.conflict)
        assertEquals("3.0.0", repository.getPlugin("net.example.source")!!.versionName)
        assertEquals(sourceJs("v3-again"), File(installRoot, "net.example.source/source.js").readText())

        // With consent the same archive installs.
        val accepted = install(
            installer().prepareImport(
                pluginArchive(manifestJson = manifest(versionCode = 2, versionName = "2.0.0"), script = sourceJs("v2")),
                installRoot,
            ),
            allowDowngrade = true,
        )
        assertEquals("2.0.0", accepted.installed.single().versionName)
        assertEquals(sourceJs("v2"), File(installRoot, "net.example.source/source.js").readText())
    }

    // ------------------------------------------------------------------ limits

    @Test
    fun `an oversized manifest or entry script is refused`() = runBlocking<Unit> {
        val manifestFailure = installer(PluginImportLimits(maxManifestBytes = 32))
            .prepareImport(pluginArchive(), installRoot)
            .failed.single()
        assertContains(manifestFailure.reason, "manifest is too large")
        assertEquals("manifest.json", manifestFailure.rootPath)

        val entryFailure = installer(PluginImportLimits(maxEntryScriptBytes = 4))
            .prepareImport(pluginArchive(script = sourceJs("longer than four bytes")), installRoot)
            .failed.single()
        assertContains(entryFailure.reason, "Plugin entry is too large")
        assertNull(entryFailure.pluginId, "a validation failure is reported without the manifest's identity")
    }

    @Test
    fun `an oversized plugin fails at install time`() = runBlocking<Unit> {
        // The limits live on the installer instance, not on the session, so the same instance has to do both halves.
        val sizedInstaller = installer(PluginImportLimits(maxSinglePluginBytes = 16))
        val session = sizedInstaller.prepareImport(
            pluginArchive(extra = listOf("assets/big.txt" to "y".repeat(64))),
            installRoot,
        )
        assertEquals(1, session.candidates.size, "the archive is within the archive-wide limits")
        assertFalse(File(installRoot, "net.example.source").exists(), "nothing is installed yet")

        val result = sizedInstaller.installPrepared(session)

        assertTrue(result.installed.isEmpty())
        assertContains(result.failed.single().reason, "Plugin is too large")
        assertNull(repository.getPlugin("net.example.source"))
        assertEquals(emptyList(), installRoot.listFiles()?.toList().orEmpty().map { it.name })
    }

    @Test
    fun `archive wide limits are enforced while extracting`() = runBlocking<Unit> {
        val tooManyFiles = assertFailsWith<IllegalArgumentException> {
            installer(PluginImportLimits(maxFileCountPerArchive = 2))
                .prepareImport(pluginArchive(extra = listOf("a.js" to "a", "b.js" to "b")), installRoot)
        }
        assertContains(tooManyFiles.message.orEmpty(), "Archive contains too many files")

        val tooLarge = assertFailsWith<IllegalArgumentException> {
            installer(PluginImportLimits(maxTotalUncompressedBytes = 64))
                .prepareImport(pluginArchive(extra = listOf("big.txt" to "y".repeat(4096))), installRoot)
        }
        assertContains(tooLarge.message.orEmpty(), "Archive is too large after extraction")

        val tooDeep = assertFailsWith<IllegalArgumentException> {
            installer(PluginImportLimits(maxDepth = 2))
                .prepareImport(pluginArchive(extra = listOf("a/b/c/d/source.js" to "deep")), installRoot)
        }
        assertContains(tooDeep.message.orEmpty(), "Zip entry is too deep")

        assertEquals(emptyList(), installRoot.listFiles()?.toList().orEmpty().map { it.name })
    }

    @Test
    fun `too many plugins in one archive is refused`() = runBlocking<Unit> {
        val failure = assertFailsWith<IllegalArgumentException> {
            installer(PluginImportLimits(maxPluginCountPerArchive = 1)).prepareImport(
                archive(
                    "a/manifest.json" to manifest(id = "net.example.a", name = "A"),
                    "a/source.js" to "a",
                    "b/manifest.json" to manifest(id = "net.example.b", name = "B"),
                    "b/source.js" to "b",
                ),
                installRoot,
            )
        }

        assertContains(failure.message.orEmpty(), "Archive contains too many plugins")
        assertEquals(emptyList(), installRoot.listFiles()?.toList().orEmpty().map { it.name })
    }

    // -------------------------------------------------------------- validation

    @Test
    fun `an unsupported api version is refused at import time`() = runBlocking<Unit> {
        val tooNew = installer()
            .prepareImport(pluginArchive(manifestJson = manifest(apiVersion = 99)), installRoot)
            .failed.single()
        assertContains(tooNew.reason, "Unsupported plugin apiVersion: 99")

        val hostTooOld = installer()
            .prepareImport(pluginArchive(manifestJson = manifest(minHostApiVersion = 99)), installRoot)
            .failed.single()
        assertContains(hostTooOld.reason, "Unsupported minHostApiVersion: 99")
    }

    @Test
    fun `a malformed manifest identity is refused`() = runBlocking<Unit> {
        fun reasonFor(manifestJson: String): String = runBlocking {
            installer().prepareImport(pluginArchive(manifestJson = manifestJson), installRoot).failed.single().reason
        }

        assertContains(reasonFor(manifest(id = "nodots")), "Plugin id must use reverse-domain format")
        assertContains(reasonFor(manifest(id = "net.9example")), "Plugin id must use reverse-domain format")
        assertContains(reasonFor(manifest(name = "  ")), "Plugin name is required")
        assertContains(reasonFor(manifest(versionCode = 0)), "Plugin versionCode must be >= 1")
    }

    @Test
    fun `the entry script must exist and be JavaScript`() = runBlocking<Unit> {
        fun reasonFor(manifestJson: String, vararg extra: Pair<String, String>): String = runBlocking {
            installer().prepareImport(pluginArchive(manifestJson = manifestJson, extra = extra.toList()), installRoot)
                .failed.single().reason
        }

        assertContains(reasonFor(manifest(entry = "missing.js")), "Plugin entry not found: missing.js")
        assertContains(
            reasonFor(manifest(entry = "source.txt"), "source.txt" to "x"),
            "Plugin entry must be a .js file",
        )
    }

    @Test
    fun `include dirs and icons must exist inside the plugin`() = runBlocking<Unit> {
        fun reasonFor(manifestJson: String, vararg extra: Pair<String, String>): String = runBlocking {
            installer().prepareImport(pluginArchive(manifestJson = manifestJson, extra = extra.toList()), installRoot)
                .failed.single().reason
        }

        assertContains(reasonFor(manifest(includeDirs = listOf("lib"))), "includeDir not found: lib")
        assertContains(reasonFor(manifest(includeDirs = listOf("."))), "Unsafe includeDir: .")
        assertContains(reasonFor(manifest(icon = "missing.png")), "Icon not found: missing.png")
        assertContains(
            reasonFor(manifest(icon = "icon.svg"), "icon.svg" to "<svg/>"),
            "Unsupported icon type: icon.svg",
        )
    }

    @Test
    fun `a valid icon is recorded as an absolute path`() = runBlocking<Unit> {
        val session = installer().prepareImport(
            pluginArchive(manifestJson = manifest(icon = "assets/icon.png"), extra = listOf("assets/icon.png" to "png")),
            installRoot,
        )
        assertEquals(1, session.candidates.size)

        val installed = install(session).installed.single()

        assertEquals(File(installed.pluginDir, "assets/icon.png").absolutePath, installed.iconPath)
        assertTrue(File(assertNotNull(installed.iconPath)).isFile)
    }

    @Test
    fun `a string reference without i18n resources is refused`() = runBlocking<Unit> {
        fun manifestWithReference(withCatalog: Boolean): String = buildJsonObject {
            put("id", "net.example.source")
            put("name", "@title")
            put("versionCode", 1)
            put("versionName", "1.0.0")
            put("apiVersion", 5)
            put("minHostApiVersion", 4)
            if (withCatalog) {
                put(
                    "i18n",
                    buildJsonObject {
                        put("defaultLocale", "en")
                        put("resources", buildJsonObject { put("en", "en.json") })
                    },
                )
            }
        }.toString()

        val failure = installer()
            .prepareImport(pluginArchive(manifestJson = manifestWithReference(false)), installRoot)
            .failed.single()
        assertContains(failure.reason, "String references require i18n resources")

        val session = installer().prepareImport(
            pluginArchive(
                manifestJson = manifestWithReference(true),
                extra = listOf("en.json" to """{"title":"Example"}"""),
            ),
            installRoot,
        )

        assertEquals(1, session.candidates.size)
        assertEquals(1, install(session).installed.size)
    }

    // --------------------------------------------------------------- reporting

    @Test
    fun `a rejected plugin is reported to the app log`() = runBlocking<Unit> {
        installer().prepareImport(pluginArchive(manifestJson = manifest(entry = "missing.js")), installRoot)

        assertEquals(1, logs.exceptions.size)
        val recorded = logs.exceptions.single()
        assertEquals("Plugin import candidate validation failed", recorded.message)
        assertContains(recorded.throwable.message.orEmpty(), "Plugin entry not found: missing.js")
    }

    @Test
    fun `the installed row keeps per-capability flags and ordering`() = runBlocking<Unit> {
        val first = install(
            installer().prepareImport(
                pluginArchive(manifestJson = manifest(id = "net.example.a", name = "A"), script = sourceJs("a")),
                installRoot,
            ),
            enabled = true,
        ).installed.single()
        val second = install(
            installer().prepareImport(
                pluginArchive(manifestJson = manifest(id = "net.example.b", name = "B"), script = sourceJs("b")),
                installRoot,
            ),
        ).installed.single()

        assertTrue(first.enabled && first.metadataEnabled && first.lyricsEnabled && first.coverEnabled)
        assertFalse(second.enabled || second.metadataEnabled || second.lyricsEnabled || second.coverEnabled)
        assertEquals(0, first.sortOrder)
        assertEquals(1, second.sortOrder)
        assertEquals(first.sortOrder, first.metadataSortOrder)
        assertEquals(first.sortOrder, first.lyricsSortOrder)
        assertEquals(first.sortOrder, first.coverSortOrder)
    }

    @Test
    fun `a plugin with no declared capabilities defaults to searchSongs`() = runBlocking<Unit> {
        val installed = install(installer().prepareImport(pluginArchive(), installRoot)).installed.single()

        assertEquals(
            setOf(PluginCapability.SEARCH_SONGS),
            json.decodeFromString<Set<PluginCapability>>(installed.capabilitiesJson),
        )
    }

    @Test
    fun `discarding a preparation removes its directory`() = runBlocking<Unit> {
        val session = installer().prepareImport(pluginArchive(), installRoot)
        assertTrue(session.tempDir.isDirectory)

        installer().discardImport(session)

        assertFalse(session.tempDir.exists())
        assertNull(repository.getPlugin("net.example.source"), "discarding prepares nothing for install")
    }

    // ------------------------------------------------------- manifest metadata

    @Test
    fun `installed manifest metadata is synchronized from disk once`() = runBlocking<Unit> {
        val syncInstaller = installer()
        val installed = install(
            syncInstaller.prepareImport(
                pluginArchive(
                    manifestJson = manifest(apiVersion = 5, minHostApiVersion = 4, capabilities = listOf("searchSongs")),
                ),
                installRoot,
            ),
        ).installed.single()
        assertEquals(5, installed.apiVersion)

        // Simulate an app upgrade: the manifest on disk is newer than the row.
        val manifestFile = File(installed.pluginDir, "manifest.json")
        manifestFile.writeText(manifest(apiVersion = 4, capabilities = listOf("searchSongs", "getLyrics")))
        syncInstaller.synchronizeInstalledPluginManifestMetadata()

        val synchronized = repository.getPlugin("net.example.source")!!
        assertEquals(4, synchronized.apiVersion)
        assertEquals(4, synchronized.minHostApiVersion)
        assertContains(synchronized.capabilitiesJson, "getLyrics")
        assertEquals(installed.versionCode, synchronized.versionCode, "only the contract columns are synchronized")
        assertEquals(installed.name, synchronized.name)

        // The same instance does not read the manifests again.
        manifestFile.writeText(manifest(apiVersion = 3))
        syncInstaller.synchronizeInstalledPluginManifestMetadata()
        assertEquals(4, repository.getPlugin("net.example.source")!!.apiVersion)
    }

    @Test
    fun `a missing installed manifest is logged and does not stop the others`() = runBlocking<Unit> {
        val capture = PlatformLogCapture().install()
        try {
            val good = install(
                installer().prepareImport(
                    pluginArchive(manifestJson = manifest(id = "net.example.good", name = "Good", apiVersion = 5)),
                    installRoot,
                ),
            ).installed.single()
            install(
                installer().prepareImport(
                    pluginArchive(manifestJson = manifest(id = "net.example.gone", name = "Gone")),
                    installRoot,
                ),
            )
            File(installRoot, "net.example.gone/manifest.json").delete()
            File(good.pluginDir, "manifest.json")
                .writeText(manifest(id = "net.example.good", name = "Good", apiVersion = 4))

            installer().synchronizeInstalledPluginManifestMetadata()

            assertEquals(4, repository.getPlugin("net.example.good")!!.apiVersion, "the healthy plugin was synchronized")
            assertEquals(5, repository.getPlugin("net.example.gone")!!.apiVersion)
            assertTrue(
                capture.warnings.any { it.contains("net.example.gone") },
                "the unreadable manifest is reported: ${capture.lines}",
            )
        } finally {
            capture.restore()
        }
    }

    @Test
    fun `a manifest whose id no longer matches the row is not applied`() = runBlocking<Unit> {
        val capture = PlatformLogCapture().install()
        try {
            val installed = install(
                installer().prepareImport(pluginArchive(manifestJson = manifest(apiVersion = 5)), installRoot),
            ).installed.single()
            File(installed.pluginDir, "manifest.json").writeText(manifest(id = "net.example.other", apiVersion = 4))

            installer().synchronizeInstalledPluginManifestMetadata()

            assertEquals(5, repository.getPlugin("net.example.source")!!.apiVersion)
            assertTrue(capture.warnings.any { it.contains("net.example.source") }, capture.lines.toString())
        } finally {
            capture.restore()
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun installer(limits: PluginImportLimits = PluginImportLimits()) = SourcePluginInstaller(
        repository = repository,
        json = json,
        appLogRepository = logs,
        limits = limits,
    )

    private suspend fun install(
        session: PluginImportSession,
        enabled: Boolean = false,
        selectedRoots: Set<String>? = null,
        allowDowngrade: Boolean = false,
    ): PluginInstallResult = installer().installPrepared(
        session = session,
        enabled = enabled,
        selectedRoots = selectedRoots,
        allowDowngrade = allowDowngrade,
    ).also { result ->
        assertEquals(
            emptyList(),
            result.failed.map { "${it.displayName}: ${it.reason}" },
            "the fixture was expected to install cleanly",
        )
    }

    /** A single-plugin archive: `manifest.json`, the entry script and anything else the case needs. */
    private fun pluginArchive(
        manifestJson: String = manifest(),
        script: String = sourceJs("x"),
        extra: List<Pair<String, String>> = emptyList(),
    ): InputStream = archive(
        *(
            listOf("manifest.json" to manifestJson, "source.js" to script) + extra
            ).toTypedArray()
    )

    /** A raw archive, keyed by zip entry name. */
    private fun archive(vararg entries: Pair<String, String>): InputStream {
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

    private fun manifest(
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
    }.toString()

    private fun sourceJs(body: String): String = "globalThis.searchSongs = () => \"$body\";"
}
