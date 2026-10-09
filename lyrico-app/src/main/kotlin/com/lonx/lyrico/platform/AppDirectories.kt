package com.lonx.lyrico.platform

import com.lonx.lyrico.data.LyricoDatabase
import java.io.File

/**
 * Where this installation keeps its files: the database, both preference stores and the caches.
 *
 * ### Portable by decision
 *
 * The desktop build is portable first: everything lives in a `data` folder next to the executable,
 * so the app can be unzipped anywhere and moved or backed up as one directory, and uninstalling it
 * means deleting that one folder. Android's answer to this question was `Context.filesDir`, which
 * has no desktop equivalent and no reason to exist here.
 *
 * Portability has one hazard the Android layout could not have: a copy installed under
 * `C:\Program Files` cannot create files next to its own executable, because that tree is
 * administrator-owned. Rather than fail to start, [resolve] probes the portable folder for real
 * (it creates a file) and falls back to the per-user `%LOCALAPPDATA%\Lyrico` when the probe fails,
 * recording which one it picked in [isPortable]. The chosen directory is written to the app log at
 * startup, so "where did my library go" is always answerable.
 *
 * A `-Dlyrico.data.dir=<path>` override wins over both and is taken literally: an explicit path
 * that cannot be written is a configuration error, so [resolve] throws instead of quietly using
 * somewhere else.
 */
data class AppDirectories(
    /** The installation's data folder. Every other path in this class is derived from it. */
    val root: File,
    /**
     * Whether [root] is the portable `data` folder next to the executable (`true`) or the
     * per-user `%LOCALAPPDATA%\Lyrico` fallback (`false`). Reported at startup; nothing behaves
     * differently because of it, so a user who copies a fallback folder to a portable location
     * keeps their library.
     */
    val isPortable: Boolean,
) {

    /** The directory passed to [com.lonx.lyrico.data.openLyricoDatabase]. */
    val databaseDir: File get() = root

    /** The Room database file, named by the schema-owning class rather than duplicated here. */
    val databaseFile: File get() = File(root, LyricoDatabase.DATABASE_FILE_NAME)

    /** The `Preferences` store holding every setting except the edit-field configuration. */
    val settingsFile: File get() = File(root, "settings.preferences_pb")

    /**
     * The edit-field configuration store, kept separate from [settingsFile] because Android kept it
     * separate (its own `Context.editFieldConfigDataStore`) and the two are written by different
     * screens; one store would make every field toggle rewrite the whole settings blob.
     */
    val editFieldConfigFile: File get() = File(root, "edit_field_config.preferences_pb")

    /** Root for regenerable data. Deleting it must never lose library rows. */
    val cacheDir: File get() = File(root, "cache")

    /** OkHttp's response cache (Android used `Context.cacheDir/http_cache`). */
    val httpCacheDir: File get() = File(cacheDir, "http_cache")

    /** Cached cover art extracted from tags. */
    val coverCacheDir: File get() = File(cacheDir, "covers")

    /** Creates the data folder and the cache folders. Called once, before the DI graph is built. */
    fun prepare(): AppDirectories {
        // Every folder the app opens by path is created here, so nothing downstream has to depend on
        // OkHttp's or the image loader's own directory handling (Android got these from `Context`).
        listOf(root, cacheDir, httpCacheDir, coverCacheDir).forEach { it.mkdirs() }
        return this
    }

    companion object {
        /** System property that overrides the resolved data folder. */
        const val DATA_DIR_PROPERTY: String = "lyrico.data.dir"

        /** The portable folder that sits next to the executable. */
        const val PORTABLE_DIR_NAME: String = "data"

        /** The per-user fallback used when the portable folder is not writable. */
        const val FALLBACK_DIR_NAME: String = "Lyrico"

        /**
         * The portable `data` folder under [installDir], or the per-user fallback when the
         * executable's own folder cannot be written.
         */
        fun resolve(
            overridePath: String? = System.getProperty(DATA_DIR_PROPERTY),
            installDir: File = defaultInstallDir(),
            fallbackRoot: File = defaultFallbackRoot(),
        ): AppDirectories {
            if (!overridePath.isNullOrBlank()) {
                val explicit = File(overridePath).absoluteFile
                check(isWritableDirectory(explicit)) {
                    "-D$DATA_DIR_PROPERTY=$overridePath cannot be created or written to"
                }
                return AppDirectories(explicit, isPortable = false)
            }

            val portable = File(installDir, PORTABLE_DIR_NAME)
            if (isWritableDirectory(portable)) return AppDirectories(portable, isPortable = true)

            check(isWritableDirectory(fallbackRoot)) {
                "Neither $portable nor $fallbackRoot can be created or written to"
            }
            return AppDirectories(fallbackRoot, isPortable = false)
        }

        /**
         * The folder the running installation lives in.
         *
         * A packaged build (`jpackage`, and therefore the MSI and the portable zip) sets
         * `jpackage.app-path` to the launcher `.exe`, so the data folder lands beside it as
         * documented. In development there is no such executable — `gradlew :lyrico-app:run` starts a
         * plain JVM whose working directory is the `lyrico-app` project — so the project directory is
         * used instead. Both cases are documented rather than probed, because the alternative (the
         * code-source location) would put development data inside `build/`, where `clean` deletes it.
         */
        fun defaultInstallDir(): File {
            val launcher = System.getProperty("jpackage.app-path")
            if (!launcher.isNullOrBlank()) {
                File(launcher).absoluteFile.parentFile?.let { return it }
            }
            return File(System.getProperty("user.dir") ?: ".").absoluteFile
        }

        /** `%LOCALAPPDATA%\Lyrico`, or the user's home when the variable is missing. */
        fun defaultFallbackRoot(): File {
            val localAppData = System.getenv("LOCALAPPDATA")
            val base = if (localAppData.isNullOrBlank()) {
                File(System.getProperty("user.home") ?: ".")
            } else {
                File(localAppData)
            }
            return File(base, FALLBACK_DIR_NAME)
        }

        /**
         * Whether [dir] exists (or can be created) and accepts a new file.
         *
         * The probe really writes: on Windows a `File.canWrite()` check is documented to be
         * unreliable for directories, and the case this exists for — an administrator-owned
         * `Program Files` tree — is exactly the one where a metadata-only check can still say yes.
         */
        private fun isWritableDirectory(dir: File): Boolean {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            return try {
                val probe = File.createTempFile("lyrico-write-probe", ".tmp", dir)
                probe.delete()
            } catch (_: Exception) {
                false
            }
        }
    }
}
