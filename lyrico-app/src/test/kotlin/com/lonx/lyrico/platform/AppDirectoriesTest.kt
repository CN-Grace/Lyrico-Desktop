package com.lonx.lyrico.platform

import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.openLyricoDatabase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The data-folder rules, exercised on the real filesystem.
 *
 * These paths decide whether the app starts at all, and every branch here depends on what the OS
 * says about a directory — that a folder can be created, or that it cannot. A mocked filesystem
 * would only re-state the branch, so each test uses real directories, including one that is real but
 * impossible to create (a path whose parent is a regular file), which is how an unwritable
 * installation is reproduced without needing administrator rights.
 */
class AppDirectoriesTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-app-dirs").toFile()

    @AfterTest
    fun cleanUp() {
        workingDir.deleteRecursively()
    }

    /** A file, used as the parent of a directory that therefore cannot be created. */
    private fun blockedParent(): File =
        File(workingDir, "not-a-directory").apply {
            parentFile.mkdirs()
            writeText("a regular file")
        }

    @Test
    fun `a writable installation folder makes the data folder portable`() {
        val installDir = File(workingDir, "install").apply { mkdirs() }

        val directories = AppDirectories.resolve(
            overridePath = null,
            installDir = installDir,
            fallbackRoot = File(workingDir, "fallback"),
        )

        assertEquals(File(installDir, "data"), directories.root)
        assertTrue(directories.isPortable)
        assertTrue(directories.root.isDirectory, "resolve should have created the data folder")
        assertFalse(File(workingDir, "fallback").exists(), "the fallback must not be touched")
    }

    @Test
    fun `an unwritable installation folder falls back to the per-user folder`() {
        val installDir = File(blockedParent(), "install")
        val fallbackRoot = File(workingDir, "fallback")

        val directories = AppDirectories.resolve(
            overridePath = null,
            installDir = installDir,
            fallbackRoot = fallbackRoot,
        )

        assertEquals(fallbackRoot, directories.root)
        assertFalse(directories.isPortable)
        assertTrue(fallbackRoot.isDirectory)
    }

    @Test
    fun `the resolved folders are derived from the root`() {
        val root = File(File(workingDir, "install"), "data")

        val directories = AppDirectories.resolve(
            overridePath = null,
            installDir = File(workingDir, "install"),
            fallbackRoot = File(workingDir, "fallback"),
        )

        assertEquals(root, directories.root)
        assertEquals(File(root, "lyrico.db"), directories.databaseFile)
        assertEquals(File(root, "settings.preferences_pb"), directories.settingsFile)
        assertEquals(File(root, "edit_field_config.preferences_pb"), directories.editFieldConfigFile)
        assertEquals(File(root, "cache"), directories.cacheDir)
        assertEquals(File(File(root, "cache"), "http_cache"), directories.httpCacheDir)
        assertEquals(File(File(root, "cache"), "covers"), directories.coverCacheDir)
        assertEquals(root, directories.databaseDir)
    }

    @Test
    fun `an explicit data directory wins over the installation folder`() {
        val installDir = File(workingDir, "install").apply { mkdirs() }
        val explicit = File(workingDir, "explicit")

        val directories = AppDirectories.resolve(
            overridePath = explicit.absolutePath,
            installDir = installDir,
            fallbackRoot = File(workingDir, "fallback"),
        )

        assertEquals(explicit, directories.root)
        assertTrue(explicit.isDirectory)
        assertFalse(File(installDir, "data").exists(), "the portable folder must not be used")
    }

    @Test
    fun `an explicit data directory that cannot be written fails loudly`() {
        val broken = File(blockedParent(), "explicit")

        val failure = assertFailsWith<IllegalStateException> {
            AppDirectories.resolve(
                overridePath = broken.absolutePath,
                installDir = File(workingDir, "install"),
                fallbackRoot = File(workingDir, "fallback"),
            )
        }

        assertTrue(
            failure.message.orEmpty().contains(broken.absolutePath),
            "the message must name the path the user configured, was: ${failure.message}",
        )
    }

    @Test
    fun `when neither folder can be written the failure names both`() {
        val failure = assertFailsWith<IllegalStateException> {
            AppDirectories.resolve(
                overridePath = null,
                installDir = File(blockedParent(), "install"),
                fallbackRoot = File(blockedParent(), "fallback"),
            )
        }

        assertTrue(failure.message.orEmpty().contains("not-a-directory"), "was: ${failure.message}")
    }

    @Test
    fun `prepare creates the cache folders and the database lands in the root`() {
        val directories = AppDirectories.resolve(
            overridePath = null,
            installDir = File(workingDir, "install"),
            fallbackRoot = File(workingDir, "fallback"),
        ).prepare()

        assertTrue(directories.cacheDir.isDirectory)
        assertTrue(directories.httpCacheDir.isDirectory)

        // The real proof that this root is the one the app can use: open the library database in it,
        // exactly as the DI graph does, write one row through a real DAO, and find the file on disk
        // afterwards. The read is not optional -- Room opens its connection lazily, so only a query
        // proves the file path is usable.
        val database = openLyricoDatabase(directories.databaseDir)
        try {
            runBlocking<Unit> {
                database.folderDao().insert(FolderEntity(path = """H:\Music"""))
                assertEquals(1, database.folderDao().getAllFoldersOnce().size)
            }
            assertTrue(directories.databaseFile.isFile, "expected ${directories.databaseFile}")
        } finally {
            database.close()
        }
    }

    @Test
    fun `resolving twice returns the same data folder`() {
        val installDir = File(workingDir, "install").apply { mkdirs() }

        val first = AppDirectories.resolve(null, installDir, File(workingDir, "fallback"))
        val second = AppDirectories.resolve(null, installDir, File(workingDir, "fallback"))

        assertEquals(first, second)
    }

    @Test
    fun `the default installation folder is a real directory`() {
        // Whatever the launcher is (packaged or a development JVM), the data folder's parent has to
        // exist, or the probe would send every user to the fallback.
        assertTrue(AppDirectories.defaultInstallDir().isDirectory)
    }

    @Test
    fun `the default fallback folder is under the per-user application data`() {
        val fallback = AppDirectories.defaultFallbackRoot()

        assertEquals(AppDirectories.FALLBACK_DIR_NAME, fallback.name)
        val localAppData = System.getenv("LOCALAPPDATA")
        if (!localAppData.isNullOrBlank()) {
            assertEquals(File(localAppData), fallback.parentFile)
        }
    }
}
