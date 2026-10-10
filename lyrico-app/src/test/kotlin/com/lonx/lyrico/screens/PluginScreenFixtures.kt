package com.lonx.lyrico.screens

import com.lonx.lyrico.data.model.entity.SourcePluginEntity
import com.lonx.lyrico.platform.FileOpenPicker
import com.lonx.lyrico.plugin.source.SourcePluginInstaller
import com.lonx.lyrico.plugin.support.pluginArchive
import com.lonx.lyrico.plugin.support.pluginManifestJson
import com.lonx.lyrico.plugin.support.sourceScript
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * What the three plugin-driven screens need in order to be tested for real.
 *
 * The plugin screens are unlike the library pages: their data does not come from a database row the
 * test can insert, it comes from an **installed plugin** -- an archive on disk, a manifest, a
 * database row, and (when the screen actually searches) a live QuickJS engine. Faking any of that
 * would test the fake, so these helpers install through the *shipped* [SourcePluginInstaller] into
 * the running Koin graph's own install root. The screens' tests then compose the real route from
 * `LyricoNavHost` and observe the result.
 *
 * The archives are built by `PluginSandbox`'s builders (`pluginArchive`/`pluginManifestJson`/
 * `sourceScript`) rather than re-implemented here, so the fixture a screen test installs is
 * byte-identical in shape to the one `PluginSourceEndToEndTest` proves the runtime can execute.
 */
object PluginScreenFixtures {

    /**
     * Installs one plugin into [installRoot] and returns the stored row.
     *
     * `enabled = true` is the default because a search screen can only reach a source whose plugin is
     * enabled, and a test that forgot this would fail as "no results" instead of "not installed".
     */
    fun install(
        installer: SourcePluginInstaller,
        installRoot: File,
        manifestJson: String = pluginManifestJson(),
        script: String = sourceScript("[]"),
        extra: List<Pair<String, String>> = emptyList(),
        enabled: Boolean = true,
    ): SourcePluginEntity = runBlocking {
        val session = installer.prepareImport(pluginArchive(manifestJson, script, extra), installRoot)
        check(session.failed.isEmpty()) {
            "fixture archive did not prepare: ${session.failed.map { it.reason }}"
        }
        val result = installer.installPrepared(session, enabled = enabled)
        check(result.failed.isEmpty()) {
            "fixture plugin did not install: ${result.failed.map { it.reason }}"
        }
        result.installed.single()
    }

    /**
     * Writes an archive to a real `.zip` file, which is what the file-open dialog would hand back.
     *
     * The picker seam returns a path, not a stream, so the test has to produce an actual file that the
     * installer can open -- the bytes never travel in memory, which is the point of asserting on this
     * path rather than on `installPrepared`.
     */
    fun archiveFile(
        directory: File,
        fileName: String = "plugin.zip",
        manifestJson: String = pluginManifestJson(),
        script: String = sourceScript("[]"),
    ): File {
        val file = File(directory, fileName)
        pluginArchive(manifestJson, script).use { input ->
            file.outputStream().use(input::copyTo)
        }
        check(file.length() > 0) { "fixture archive was written empty" }
        return file
    }
}

/** A [FileOpenPicker] that answers with a fixed file, recording that it was asked at all. */
class RecordingFileOpenPicker(private val target: File?) : FileOpenPicker {
    var pickCount: Int = 0
        private set

    override suspend fun pick(): File? {
        pickCount++
        return target
    }
}

/** A temp directory that the tests can clean up wholesale. */
fun screenTempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()
