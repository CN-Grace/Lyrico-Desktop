package com.lonx.lyrico.worker.processor

import com.lonx.lyrico.data.model.ExportDestination
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class BatchExportTaskConfigTest {

    /**
     * The decoder the processor itself uses: lenient, so a task row written by the Android build (which had
     * a `destinationTreeUri` instead of a plain directory path) still decodes instead of failing the task.
     * `BatchExportProcessor.CONFIG_JSON` is the source of truth for this setting; the test mirrors it rather
     * than using the strict default.
     */
    private val configJson = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyConfigDefaultsToSelectedDirectory() {
        val config = configJson.decodeFromString<BatchExportTaskConfig>(
            """{"destinationTreeUri":"content://documents/tree/music","concurrency":3}"""
        )

        assertEquals(ExportDestination.SELECTED_DIRECTORY, config.destination)
        assertEquals(3, config.concurrency)
        // The Android-era `destinationTreeUri` is *ignored*, not read: the desktop config carries a plain
        // `destinationDirectory` path (see `BatchExportProcessor`'s KDoc), so a task row from Android decodes
        // -- which is what the tolerant `Json` at that call site is for -- but contributes no destination.
        assertNull(config.destinationDirectory)
    }

    @Test
    fun audioDirectoryConfigDoesNotRequireOneSharedDirectory() {
        val encoded = configJson.encodeToString(
            BatchExportTaskConfig.serializer(),
            BatchExportTaskConfig(destination = ExportDestination.AUDIO_DIRECTORY)
        )

        val decoded = configJson.decodeFromString<BatchExportTaskConfig>(encoded)
        assertEquals(ExportDestination.AUDIO_DIRECTORY, decoded.destination)
        assertNull(decoded.destinationDirectory)
    }
}
