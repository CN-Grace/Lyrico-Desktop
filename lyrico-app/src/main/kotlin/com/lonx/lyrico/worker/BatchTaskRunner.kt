package com.lonx.lyrico.worker

import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.utils.logging.PlatformLog
import com.lonx.lyrico.viewmodel.LyricsFormatConfig
import com.lonx.lyrico.worker.processor.BatchExportTaskConfig
import com.lonx.lyrico.worker.processor.BatchTaskProcessorFactory
import com.lonx.lyrico.worker.processor.BatchTaskSkippedException
import com.lonx.lyrico.worker.processor.EditTagsTaskConfig
import com.lonx.lyrico.worker.processor.MatchMetadataTaskConfig
import com.lonx.lyrico.worker.processor.RenameFilesTaskConfig
import com.lonx.lyrico.worker.processor.ReplayGainTaskConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs one batch task to completion: the desktop counterpart of Android's `BatchTaskWorker`.
 *
 * Android wrapped this body in a `CoroutineWorker`, so WorkManager owned the process lifetime, the
 * foreground notification and the "stop requested" flag. On the desktop all of that is carried by
 * the coroutine that calls [run] itself: [BatchTaskScheduler] launches it, cancelling it is the way
 * to stop a task, and progress reaches the user through the task row in the database
 * (`BatchTaskRepository.updateProgressFromItems`) instead of a notification. The parts that were
 * only plumbing for those platform services -- the wake lock, the foreground notification and the
 * notification's title lookup -- are gone; the task bookkeeping, the concurrency limit, the
 * per-item state machine and the AppLog summary are ported as they were.
 *
 * Two deliberate differences from the Android original, both covered by tests:
 *
 *  * The processor is resolved *before* the task is marked RUNNING. Android marked the task RUNNING
 *    and only then looked the processor up, so a task type without a registered processor was left
 *    stuck in RUNNING until the next start-up's orphan recovery (`markOrphanedTasksFailed`) found
 *    it. Here such a task goes straight to FAILED with the reason -- the same end state, without
 *    the wedged window in between.
 *  * Cancellation is the cancellation of the calling coroutine rather than WorkManager's stop flag.
 *    The flag is read once, right after the items have finished, *before* the final phase runs
 *    inside `NonCancellable`: inside `NonCancellable` the context is active again, so reading it
 *    there would always report "not cancelled" and a cancelled task would be marked as succeeded.
 */
class BatchTaskRunner(
    private val taskRepository: BatchTaskRepository,
    private val processorFactory: BatchTaskProcessorFactory,
    private val appLogRepository: AppLogRepository
) {

    suspend fun run(taskId: String) {
        if (taskId.isEmpty()) return

        val task = taskRepository.getTask(taskId) ?: return
        val processor = try {
            processorFactory.create(task.type)
        } catch (e: IllegalArgumentException) {
            // No registered processor: this cannot happen for a launchable type, but a database
            // carried over from Android (or a future type whose processor is not ported yet) can
            // still hold one. Record it and stop rather than leaving the task RUNNING forever.
            taskRepository.markFailed(taskId, e.message)
            logBatch(
                level = AppLogLevel.ERROR,
                message = "Batch task failed: ${task.type} (no processor registered)",
                detail = e.stackTraceToString(),
                relatedId = taskId
            )
            return
        }

        val total = task.total
        val startedAt = System.currentTimeMillis()
        val configSummary = buildConfigSummary(task.type, task.configJson)

        taskRepository.markRunning(taskId)

        val items = taskRepository.getPendingItems(taskId)

        if (items.isEmpty()) {
            taskRepository.markSucceeded(taskId)
            logBatch(
                level = AppLogLevel.INFO,
                message = "Batch task finished: ${task.type} (0/$total processed)",
                detail = buildBatchSummary(
                    taskId = taskId,
                    taskType = task.type,
                    status = "succeeded",
                    configSummary = configSummary,
                    startedAt = startedAt,
                    total = total,
                    pending = 0,
                    concurrency = 0,
                    processed = 0,
                    success = 0,
                    skipped = 0,
                    failure = 0,
                    itemDetails = emptyList()
                ),
                relatedId = taskId
            )
            return
        }

        val itemTotal = items.size
        val concurrency = parseConcurrency(task.configJson)
        val semaphore = Semaphore(concurrency)
        val processedCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val itemDetails = ConcurrentLinkedQueue<String>()

        try {
            coroutineScope {
                val jobs = items.map { item ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            if (!currentCoroutineContext().isActive) return@async
                            try {
                                taskRepository.markItemRunning(item.itemId)
                                val result = processor.process(task, item) { progress ->
                                    taskRepository.updateItemProgress(item.itemId, progress)
                                }
                                if (result.updatedFilePath != null && result.updatedFileName != null) {
                                    taskRepository.updateItemFileInfo(
                                        itemId = item.itemId,
                                        filePath = result.updatedFilePath,
                                        fileName = result.updatedFileName
                                    )
                                }
                                taskRepository.markItemSucceeded(item.itemId, result.resultJson)
                                successCount.incrementAndGet()
                            } catch (e: BatchTaskSkippedException) {
                                PlatformLog.i(
                                    TAG,
                                    "Item processing skipped: ${item.fileName}, reason=${e.message ?: "No reason"}"
                                )
                                taskRepository.markItemSkipped(item.itemId, e.message)
                                skippedCount.incrementAndGet()
                                itemDetails.add("SKIPPED ${item.fileName}: ${e.message ?: "No reason"}")
                            } catch (e: Exception) {
                                PlatformLog.e(TAG, "Item processing failed: ${item.fileName}", e)
                                taskRepository.markItemFailed(item.itemId, e.message)
                                failureCount.incrementAndGet()
                                itemDetails.add(
                                    buildString {
                                        appendLine("FAILED ${item.fileName}: ${e.message ?: e::class.java.simpleName}")
                                        append(e.stackTraceToString())
                                    }
                                )
                            } finally {
                                processedCount.incrementAndGet()
                                taskRepository.updateProgressFromItems(taskId, item.fileName)
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        } finally {
            // Read before entering NonCancellable: there the context is active again, so this would
            // report "not cancelled" for a task that was in fact cancelled.
            val stopped = !currentCoroutineContext().isActive
            withContext(NonCancellable) {
                if (stopped) {
                    taskRepository.markCancelled(taskId)
                    logBatch(
                        level = AppLogLevel.WARNING,
                        message = buildBatchMessage(
                            task.type,
                            "cancelled",
                            processedCount.get(),
                            successCount.get(),
                            skippedCount.get(),
                            failureCount.get()
                        ),
                        detail = buildBatchSummary(
                            taskId = taskId,
                            taskType = task.type,
                            status = "cancelled",
                            configSummary = configSummary,
                            startedAt = startedAt,
                            total = total,
                            pending = itemTotal,
                            concurrency = concurrency,
                            processed = processedCount.get(),
                            success = successCount.get(),
                            skipped = skippedCount.get(),
                            failure = failureCount.get(),
                            itemDetails = itemDetails.toList()
                        ),
                        relatedId = taskId
                    )
                } else {
                    taskRepository.markSucceeded(taskId)
                    logBatch(
                        level = if (failureCount.get() > 0) AppLogLevel.WARNING else AppLogLevel.INFO,
                        message = buildBatchMessage(
                            task.type,
                            "finished",
                            processedCount.get(),
                            successCount.get(),
                            skippedCount.get(),
                            failureCount.get()
                        ),
                        detail = buildBatchSummary(
                            taskId = taskId,
                            taskType = task.type,
                            status = if (failureCount.get() > 0) "finished_with_errors" else "succeeded",
                            configSummary = configSummary,
                            startedAt = startedAt,
                            total = total,
                            pending = itemTotal,
                            concurrency = concurrency,
                            processed = processedCount.get(),
                            success = successCount.get(),
                            skipped = skippedCount.get(),
                            failure = failureCount.get(),
                            itemDetails = itemDetails.toList()
                        ),
                        relatedId = taskId
                    )
                }
            }
        }
    }

    private fun parseConcurrency(configJson: String?): Int {
        if (configJson == null) return 1
        return try {
            val obj = Json.parseToJsonElement(configJson).jsonObject
            val concurrency = obj["concurrency"]?.jsonPrimitive?.int
            concurrency?.coerceIn(1, 5) ?: 3
        } catch (e: Exception) {
            3
        }
    }

    private fun buildBatchSummary(
        taskId: String,
        taskType: BatchTaskType,
        status: String,
        configSummary: String,
        startedAt: Long,
        total: Int,
        pending: Int,
        concurrency: Int,
        processed: Int,
        success: Int,
        skipped: Int,
        failure: Int,
        itemDetails: List<String>
    ): String = buildString {
        val finishedAt = System.currentTimeMillis()
        appendLine("taskId=$taskId")
        appendLine("type=$taskType")
        appendLine("status=$status")
        appendLine()
        appendLine("configuration:")
        appendLine(configSummary.prependIndent("  "))
        appendLine()
        appendLine("startedAt=$startedAt")
        appendLine("finishedAt=$finishedAt")
        appendLine("durationMs=${finishedAt - startedAt}")
        appendLine("total=$total")
        appendLine("items=$pending")
        appendLine("concurrency=$concurrency")
        appendLine("processed=$processed")
        appendLine("success=$success")
        appendLine("skipped=$skipped")
        appendLine("failure=$failure")
        if (itemDetails.isNotEmpty()) {
            appendLine()
            appendLine("items:")
            itemDetails.take(MAX_ITEM_DETAILS_IN_LOG).forEach { detail ->
                appendLine(detail.trimEnd())
                appendLine()
            }
            val omitted = itemDetails.size - MAX_ITEM_DETAILS_IN_LOG
            if (omitted > 0) {
                appendLine("... $omitted more item details omitted")
            }
        }
    }

    private fun buildConfigSummary(type: BatchTaskType, configJson: String?): String {
        if (configJson.isNullOrBlank()) return "config=(none)"
        return runCatching {
            when (type) {
                BatchTaskType.MATCH_METADATA,
                BatchTaskType.MATCH_LYRICS,
                BatchTaskType.MATCH_COVER -> summarizeMatchConfig(configJson)
                BatchTaskType.RENAME_FILES -> summarizeRenameConfig(configJson)
                BatchTaskType.EDIT_TAGS -> summarizeEditTagsConfig(configJson)
                BatchTaskType.CONVERT_LYRICS_FORMAT -> summarizeLyricsFormatConfig(configJson)
                BatchTaskType.SCAN_REPLAY_GAIN -> summarizeReplayGainConfig(configJson)
                BatchTaskType.EXPORT_LYRICS,
                BatchTaskType.EXPORT_COVER -> summarizeExportConfig(configJson)
            }
        }.getOrElse { e ->
            buildString {
                appendLine("configParseError=${e.message ?: e::class.java.simpleName}")
                appendLine("rawConfig=${configJson.take(MAX_CONFIG_LOG_LENGTH)}")
            }.trimEnd()
        }
    }

    private fun summarizeMatchConfig(configJson: String): String {
        val config = Json.decodeFromString<MatchMetadataTaskConfig>(configJson)
        return buildString {
            appendLine("concurrency=${config.concurrency}")
            appendLine("separator=${config.separator}")
            appendLine("preferFileName=${config.matchConfig.preferFileName}")
            appendLine("enabledSources=${config.enabledSourceOrderIds.joinToString(" > ").ifBlank { "(default)" }}")
            appendLine(
                "fields=${
                    config.matchConfig.targetModes.toSortedMap(compareBy { it.name })
                        .entries.joinToString(", ") { "${it.key.name}:${it.value.name}" }
                }"
            )
        }.trimEnd()
    }

    private fun summarizeRenameConfig(configJson: String): String {
        val config = Json.decodeFromString<RenameFilesTaskConfig>(configJson)
        return buildString {
            appendLine("renameFormat=${config.renameFormat}")
            appendLine("characterMappingRules=${config.characterMappingRules.size}")
            val customizedRules = config.characterMappingRules.filter { rule ->
                rule.charMappings.isNotEmpty()
            }
            appendLine("customizedMappingRules=${customizedRules.joinToString(", ") { it.name }.ifBlank { "(none)" }}")
        }.trimEnd()
    }

    private fun summarizeEditTagsConfig(configJson: String): String {
        val config = Json.decodeFromString<EditTagsTaskConfig>(configJson)
        val keep = EditTagsTaskConfig.KEEP_VALUE
        val modifiedFields = buildList {
            if (config.title != keep) add("title")
            if (config.artist != keep) add("artist")
            if (config.albumArtist != keep) add("albumArtist")
            if (config.album != keep) add("album")
            if (config.date != keep) add("date")
            if (config.genre != keep) add("genre")
            if (config.trackNumber != keep) add("trackNumber")
            if (config.discNumber != keep) add("discNumber")
            if (config.composer != keep) add("composer")
            if (config.lyricist != keep) add("lyricist")
            if (config.copyright != keep) add("copyright")
            if (config.comment != keep) add("comment")
            if (config.lyrics != keep) add("lyrics")
            if (config.ratingModified) add("rating")
            if (config.coverUri != null) add("cover")
            if (config.removeCover) add("removeFrontCover")
            if (config.lyricsOffset.isNotBlank()) add("lyricsOffset")
            if (config.replayGainTrackGain != keep) add("replayGainTrackGain")
            if (config.replayGainTrackPeak != keep) add("replayGainTrackPeak")
            if (config.replayGainAlbumGain != keep) add("replayGainAlbumGain")
            if (config.replayGainAlbumPeak != keep) add("replayGainAlbumPeak")
            if (config.replayGainReferenceLoudness != keep) add("replayGainReferenceLoudness")
            if (config.customFields.isNotEmpty()) add("customFields")
        }

        return buildString {
            appendLine("concurrency=${config.concurrency}")
            appendLine("modifiedFields=${modifiedFields.joinToString(", ").ifBlank { "(none)" }}")
            if (config.ratingModified) appendLine("rating=${config.rating}")
            if (config.lyricsOffset.isNotBlank()) appendLine("lyricsOffset=${config.lyricsOffset}")
            appendLine("customFieldKeys=${config.customFields.joinToString(", ") { it.key }.ifBlank { "(none)" }}")
            appendSanitizedValue("title", config.title, keep)
            appendSanitizedValue("artist", config.artist, keep)
            appendSanitizedValue("albumArtist", config.albumArtist, keep)
            appendSanitizedValue("album", config.album, keep)
            appendSanitizedValue("date", config.date, keep)
            appendSanitizedValue("genre", config.genre, keep)
            appendSanitizedValue("trackNumber", config.trackNumber, keep)
            appendSanitizedValue("discNumber", config.discNumber, keep)
            appendSanitizedValue("composer", config.composer, keep)
            appendSanitizedValue("lyricist", config.lyricist, keep)
            appendSanitizedValue("copyright", config.copyright, keep)
            appendSanitizedValue("comment", config.comment, keep)
            appendSanitizedValue("replayGainTrackGain", config.replayGainTrackGain, keep)
            appendSanitizedValue("replayGainTrackPeak", config.replayGainTrackPeak, keep)
            appendSanitizedValue("replayGainAlbumGain", config.replayGainAlbumGain, keep)
            appendSanitizedValue("replayGainAlbumPeak", config.replayGainAlbumPeak, keep)
            appendSanitizedValue("replayGainReferenceLoudness", config.replayGainReferenceLoudness, keep)
            if (config.lyrics != keep) appendLine("lyrics=(modified, ${config.lyrics.length} chars)")
            if (config.coverUri != null) appendLine("coverUri=(set)")
            if (config.removeCover) appendLine("removeFrontCover=true")
        }.trimEnd()
    }

    private fun summarizeLyricsFormatConfig(configJson: String): String {
        val config = Json.decodeFromString<LyricsFormatConfig>(configJson)
        return buildString {
            appendLine("targetFormat=${config.targetFormat?.name ?: "KEEP"}")
            appendLine("concurrency=${config.concurrency}")
            appendLine("operation=${config.operation}")
            appendLine("twoColumnMapping=${config.twoColumnMapping}")
            appendLine("threeColumnMapping=${config.threeColumnMapping}")
            appendLine("formatLineOrder=${config.formatLineOrder}")
            appendLine("removeTagLines=${config.removeTagLines}")
            appendLine("removeEmptyLines=${config.removeEmptyLines}")
        }.trimEnd()
    }

    private fun summarizeExportConfig(configJson: String): String {
        val config = Json.decodeFromString<BatchExportTaskConfig>(configJson)
        return buildString {
            appendLine("destination=${config.destination}")
            if (config.destinationDirectory != null) {
                appendLine("destinationDirectory=${config.destinationDirectory}")
            }
            appendLine("concurrency=${config.concurrency}")
        }.trimEnd()
    }

    private fun summarizeReplayGainConfig(configJson: String): String {
        val config = Json.decodeFromString<ReplayGainTaskConfig>(configJson)
        return buildString {
            appendLine("concurrency=${config.concurrency}")
            config.targetLoudness?.let { appendLine("targetLoudness=$it") }
            config.peakMode?.let { appendLine("peakMode=${it.name}") }
        }.trimEnd()
    }

    private fun StringBuilder.appendSanitizedValue(
        name: String,
        value: String,
        keep: String
    ) {
        if (value == keep) return
        appendLine("$name=${value.sanitizeConfigValue()}")
    }

    private fun String.sanitizeConfigValue(): String {
        val oneLine = replace("\r", "\\r").replace("\n", "\\n")
        return if (oneLine.length <= MAX_CONFIG_VALUE_LENGTH) {
            oneLine
        } else {
            "${oneLine.take(MAX_CONFIG_VALUE_LENGTH)}... (${length} chars)"
        }
    }

    private fun buildBatchMessage(
        type: BatchTaskType,
        status: String,
        processed: Int,
        success: Int,
        skipped: Int,
        failure: Int
    ): String {
        return "Batch task $status: $type (processed=$processed, success=$success, skipped=$skipped, failure=$failure)"
    }

    private suspend fun logBatch(
        level: AppLogLevel,
        message: String,
        detail: String? = null,
        relatedId: String? = null
    ) {
        try {
            appLogRepository.log(
                level = level,
                type = AppLogType.BATCH,
                tag = TAG,
                message = message,
                detail = detail,
                relatedId = relatedId
            )
        } catch (e: Exception) {
            PlatformLog.w(TAG, "Failed to write batch log", e)
        }
    }

    companion object {
        /**
         * Kept from Android: the tag is written into the app log and shown in the log screen, so
         * entries written before and after the port read the same there.
         */
        private const val TAG = "BatchTaskWorker"
        private const val MAX_ITEM_DETAILS_IN_LOG = 50
        private const val MAX_CONFIG_LOG_LENGTH = 8_000
        private const val MAX_CONFIG_VALUE_LENGTH = 200
    }
}
