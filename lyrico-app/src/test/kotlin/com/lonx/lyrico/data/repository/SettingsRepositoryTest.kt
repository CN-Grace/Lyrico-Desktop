package com.lonx.lyrico.data.repository

import com.lonx.lyrico.data.model.AppLanguage
import com.lonx.lyrico.data.model.ConversionMode
import com.lonx.lyrico.data.model.SettingsBackup
import com.lonx.lyrico.data.model.ThemeMode
import com.lonx.lyrico.data.model.log.LogRetentionOption
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortInfo
import com.lonx.lyrico.viewmodel.SortOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for the desktop settings store.
 *
 * Android's `SettingsRepositoryImpl` took a `Context` and used the `preferencesDataStore` delegate;
 * the port takes a `DataStore<Preferences>` created from a file path. These tests drive a **real
 * file-backed store** in a temp directory (not an in-memory fake), so they prove the settings
 * actually land in `settings.preferences_pb` and survive a fresh store instance — which is the
 * property the rest of the port depends on (library roots, scan bookkeeping, tag `separator`).
 */
class SettingsRepositoryTest {

    private val workingDir: Path = Files.createTempDirectory("lyrico-settings-test")
    private val settingsFile: Path = workingDir.resolve("settings.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()

    /** Reads and writes backups without going through the pretty printer, which is not the subject here. */
    private val backupJson = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        workingDir.toFile().deleteRecursively()
    }

    /** Opens a store on the shared settings file with its own scope, tracked for teardown. */
    private fun openRepository(): SettingsRepositoryImpl {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return SettingsRepositoryImpl(createSettingsDataStore(settingsFile, scope))
    }

    /** Cancels [scope] and waits for completion, releasing the DataStore's claim on the file. */
    private suspend fun CoroutineScope.closeAndWait() {
        cancel()
        coroutineContext.job.join()
    }

    @Test
    fun `reads documented defaults from an empty store`() = runBlocking<Unit> {
        val settings = openRepository()

        assertEquals(SortInfo(SortBy.TITLE, SortOrder.ASC), settings.sortInfo.first())
        assertEquals("/", settings.separator.first())
        assertEquals(SettingsDefaults.RENAME_FORMAT, settings.renameFormat.first())
        assertEquals(ThemeMode.AUTO, settings.themeMode.first())
        assertEquals(ConversionMode.NONE, settings.conversionMode.first())
        assertEquals(LogRetentionOption.THIRTY_DAYS, settings.logRetentionOption.first())
        assertEquals(AppLanguage.SYSTEM, settings.appLanguage.first(), "an unset language has to mean follow system")
        assertEquals(SettingsDefaults.ALBUM_GRID_COLUMNS, settings.albumGridColumns.first())
        assertEquals(0L, settings.getLastScanTime())
        assertNull(settings.artistPosterFolder.first())
    }

    @Test
    fun `writes and reads back every kind of setting`() = runBlocking<Unit> {
        val settings = openRepository()

        settings.saveSeparator("|")
        settings.saveSortInfo(SortInfo(SortBy.DURATION, SortOrder.DESC))
        settings.saveThemeMode(ThemeMode.DARK)
        settings.saveConversionMode(ConversionMode.SIMPLIFIED_TO_TRADITIONAL)
        settings.saveLogRetentionOption(LogRetentionOption.SEVEN_DAYS)
        settings.saveAppLanguage(AppLanguage.TRADITIONAL_CHINESE_TAIWAN)
        settings.saveLastScanTime(1_700_000_000_000L)
        settings.setArtistPosterFolder("H:\\Lyrico\\posters")
        settings.refreshArtistPosters()

        assertEquals("|", settings.separator.first())
        assertEquals(SortInfo(SortBy.DURATION, SortOrder.DESC), settings.sortInfo.first())
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
        assertEquals(ConversionMode.SIMPLIFIED_TO_TRADITIONAL, settings.conversionMode.first())
        assertEquals(LogRetentionOption.SEVEN_DAYS, settings.logRetentionOption.first())
        assertEquals(AppLanguage.TRADITIONAL_CHINESE_TAIWAN, settings.appLanguage.first())
        assertEquals(1_700_000_000_000L, settings.getLastScanTime())
        assertEquals("H:\\Lyrico\\posters", settings.artistPosterFolder.first())
        assertEquals(1L, settings.artistPosterRevision.first())
    }

    @Test
    fun `settings survive closing and reopening the store file`() = runBlocking<Unit> {
        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += firstScope
        val first = SettingsRepositoryImpl(createSettingsDataStore(settingsFile, firstScope))
        first.saveSeparator("|")
        first.saveSortInfo(SortInfo(SortBy.FILE_SIZE, SortOrder.DESC))
        first.setArtistPosterFolder("H:\\Lyrico\\posters")
        first.saveAppLanguage(AppLanguage.TRADITIONAL_CHINESE_HONG_KONG)

        // A real file was written, not just an in-memory cache.
        assertTrue(Files.exists(settingsFile), "settings file should exist after a write")
        assertTrue(Files.size(settingsFile) > 0, "settings file should not be empty")

        // Releasing the store and reopening it is the desktop equivalent of an app restart.
        firstScope.closeAndWait()
        val second = openRepository()

        assertEquals("|", second.separator.first())
        assertEquals(SortInfo(SortBy.FILE_SIZE, SortOrder.DESC), second.sortInfo.first())
        assertEquals("H:\\Lyrico\\posters", second.artistPosterFolder.first())
        assertEquals(
            AppLanguage.TRADITIONAL_CHINESE_HONG_KONG,
            second.appLanguage.first(),
            "the language did not survive a restart of the store",
        )
    }

    @Test
    fun `a backup carries the language, and import restores it`() = runBlocking<Unit> {
        val settings = openRepository()
        settings.saveAppLanguage(AppLanguage.SIMPLIFIED_CHINESE)

        val json = settings.exportSettings()
        assertEquals(
            "zh-CN",
            backupJson.decodeFromString<SettingsBackup>(json).appLanguage,
            "the backup does not carry the language tag",
        )

        // Importing into a store that is in another language is the case a user hits after a reinstall.
        settings.saveAppLanguage(AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, settings.appLanguage.first())

        assertTrue(settings.importSettings(json), "a backup exported by this version has to import")
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, settings.appLanguage.first())
    }

    @Test
    fun `an unknown language in a backup falls back to follow system`() = runBlocking<Unit> {
        val settings = openRepository()
        settings.saveAppLanguage(AppLanguage.SIMPLIFIED_CHINESE)

        // A backup written by a hand-edit or a future version: the import has to normalise rather than
        // store a tag that no longer means anything. Re-encoded rather than string-replaced, so this does
        // not depend on the backup's whitespace.
        val backup = backupJson.decodeFromString<SettingsBackup>(settings.exportSettings())
        val tampered = backupJson.encodeToString(backup.copy(appLanguage = "xx-YY"))

        assertTrue(settings.importSettings(tampered))
        assertEquals(AppLanguage.SYSTEM, settings.appLanguage.first())
    }

    @Test
    fun `clearing the artist poster folder keeps the revision counter`() = runBlocking<Unit> {
        val settings = openRepository()

        settings.setArtistPosterFolder("H:\\Lyrico\\posters")
        settings.refreshArtistPosters()
        settings.clearArtistPosterFolder()

        assertNull(settings.artistPosterFolder.first())
        assertEquals(1L, settings.artistPosterRevision.first(), "revision must not reset with the folder")
    }
}
