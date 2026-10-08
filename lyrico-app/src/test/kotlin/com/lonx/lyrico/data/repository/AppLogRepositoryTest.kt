package com.lonx.lyrico.data.repository

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.log.LogRetentionOption
import com.lonx.lyrico.data.openLyricoDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioural tests for the durable app log against a real Room database on the JVM, plus the real
 * file-backed settings store that decides whether logging is enabled and when rows are trimmed.
 *
 * The tag layer reports metadata write failures through this repository, so "a failed write is
 * recorded" is a user-visible guarantee, not an implementation detail.
 */
class AppLogRepositoryTest {

    private lateinit var workingDir: File
    private lateinit var database: LyricoDatabase
    private lateinit var settings: SettingsRepositoryImpl
    private lateinit var repository: AppLogRepositoryImpl

    @BeforeTest
    fun setUp() {
        workingDir = Files.createTempDirectory("lyrico-applog-test").toFile()
        database = openLyricoDatabase(workingDir)
        settings = SettingsRepositoryImpl(
            createSettingsDataStore(workingDir.resolve("settings.preferences_pb").toPath())
        )
        repository = AppLogRepositoryImpl(database.appLogDao(), settings)
    }

    @AfterTest
    fun tearDown() {
        database.close()
        workingDir.deleteRecursively()
    }

    @Test
    fun `logging is disabled while retention is none`() = runBlocking<Unit> {
        settings.saveLogRetentionOption(LogRetentionOption.NONE)

        repository.log(AppLogLevel.INFO, AppLogType.METADATA, "TagWriter", "should be dropped")

        assertEquals(0, repository.getLatest().size, "NONE means recording is off")
    }

    @Test
    fun `records logs and reads them newest first`() = runBlocking<Unit> {
        settings.saveLogRetentionOption(LogRetentionOption.FOREVER)

        repository.log(AppLogLevel.INFO, AppLogType.METADATA, "TagWriter", "first", relatedId = "H:\\a.flac")
        repository.log(AppLogLevel.ERROR, AppLogType.DATABASE, "Db", "second")

        val latest = repository.getLatest()
        assertEquals(listOf("second", "first"), latest.map { it.message })
        assertEquals(1, repository.observeByRelatedId("H:\\a.flac").first().size)
    }

    @Test
    fun `log exception stores the stack trace as detail`() = runBlocking<Unit> {
        settings.saveLogRetentionOption(LogRetentionOption.FOREVER)

        repository.logException(
            type = AppLogType.METADATA,
            tag = "TagWriter",
            message = "write failed",
            throwable = IllegalStateException("disk is read only"),
            relatedId = "H:\\a.flac",
        )

        val entry = repository.getLatest().single()
        assertEquals(AppLogLevel.ERROR, entry.level)
        assertEquals("write failed: disk is read only", entry.message)
        assertTrue(entry.detail?.contains("IllegalStateException") == true, "detail should carry the stack trace")
        assertEquals("H:\\a.flac", entry.relatedId)
    }

    @Test
    fun `retention policy deletes rows older than the window`() = runBlocking<Unit> {
        settings.saveLogRetentionOption(LogRetentionOption.SEVEN_DAYS)
        val dao = database.appLogDao()
        // A row from 30 days ago and one from now, both inserted directly so the tests control time.
        val now = System.currentTimeMillis()
        dao.insert(
            com.lonx.lyrico.data.model.entity.AppLogEntity(
                createdAt = now - 30L * 24L * 60L * 60L * 1000L,
                level = AppLogLevel.INFO,
                type = AppLogType.APP,
                tag = "Old",
                message = "ancient",
            )
        )
        dao.insert(
            com.lonx.lyrico.data.model.entity.AppLogEntity(
                level = AppLogLevel.INFO,
                type = AppLogType.APP,
                tag = "New",
                message = "recent",
            )
        )

        repository.applyRetentionPolicy()

        assertEquals(listOf("recent"), repository.getLatest().map { it.message })
    }

    @Test
    fun `exportText contains a header and every entry`() = runBlocking<Unit> {
        settings.saveLogRetentionOption(LogRetentionOption.FOREVER)
        repository.log(AppLogLevel.WARNING, AppLogType.METADATA, "TagWriter", "cover missing")
        repository.log(AppLogLevel.INFO, AppLogType.METADATA, "TagWriter", "title written")

        val exported = repository.exportText()

        assertTrue(exported.startsWith("Lyrico log export"), "export should be self describing")
        assertTrue(exported.contains("Count: 2"), "export should report the entry count: $exported")
        assertTrue(exported.contains("cover missing") && exported.contains("title written"))
    }

    @Test
    fun `clear removes every entry`() = runBlocking<Unit> {
        settings.saveLogRetentionOption(LogRetentionOption.FOREVER)
        repository.log(AppLogLevel.INFO, AppLogType.APP, "App", "hello")

        repository.clear()

        assertEquals(0, repository.getLatest().size)
    }
}
