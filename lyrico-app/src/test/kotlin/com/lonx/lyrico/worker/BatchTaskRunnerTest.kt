package com.lonx.lyrico.worker

import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.CharacterMappingRule
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.lyrics.LyricFormat
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.BatchTaskRepositoryImpl
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.viewmodel.LyricsFormatConfig
import com.lonx.lyrico.worker.processor.BatchTaskProcessResult
import com.lonx.lyrico.worker.processor.BatchTaskProcessor
import com.lonx.lyrico.worker.processor.BatchTaskProcessorFactory
import com.lonx.lyrico.worker.processor.BatchTaskSkippedException
import com.lonx.lyrico.worker.processor.EditTagsCustomField
import com.lonx.lyrico.worker.processor.EditTagsTaskConfig
import com.lonx.lyrico.worker.processor.RenameFilesTaskConfig
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The batch task runner -- the ported `BatchTaskWorker` -- driven by a scripted processor.
 *
 * What matters at this layer is not what a processor does with a file but what the *runner* does with
 * the task: which items it marks running and in which order, what a skip and a failure look like from
 * the outside, whether the configured concurrency is respected, what the run leaves behind in the app
 * log, and what happens when the app closes or the user cancels mid-run.
 *
 * The processor is the one collaborator that is replaced, deliberately: a real one would make it
 * impossible to produce a skip, a failure and a hang on demand, and the runner is the subject here.
 * Everything else is real -- a real SQLite database through the real repository, and the real log
 * recorder -- so the assertions are made against rows and log entries rather than against the
 * runner's own idea of what it did.
 *
 * The end-to-end path (real processors, real audio files, real tag writes) is covered by
 * `BatchTaskEngineEndToEndTest`.
 */
class BatchTaskRunnerTest {

    private lateinit var library: TestLibrary
    private lateinit var repository: BatchTaskRepository
    private lateinit var log: RecordingAppLogRepository

    private val dao get() = library.database.batchTaskDao()

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        repository = BatchTaskRepositoryImpl(batchTaskDao = dao)
        log = RecordingAppLogRepository()
    }

    @AfterTest
    fun tearDown() {
        library.close()
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    fun `a task with no pending items is finished without calling the processor`() = runBlocking<Unit> {
        val processor = ScriptedProcessor()
        val taskId = repository.createTask(BatchTaskType.EDIT_TAGS, emptyList(), configJson = null)

        runner(processor).run(taskId)

        val task = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, task.status)
        assertEquals(0, task.current)
        assertTrue(processor.startedItems.isEmpty(), "nothing to process means nobody is called")

        val entry = log.entries.single()
        assertEquals(AppLogLevel.INFO, entry.level)
        assertEquals(AppLogType.BATCH, entry.type)
        assertEquals("BatchTaskWorker", entry.tag, "the log tag is kept so old and new entries read alike")
        assertEquals("Batch task finished: EDIT_TAGS (0/0 processed)", entry.message)
        assertEquals(taskId, entry.relatedId)
        val detail = assertNotNull(entry.detail)
        assertEquals(
            listOf("total=0", "items=0", "concurrency=0", "processed=0", "success=0", "skipped=0", "failure=0"),
            detail.lines().filter { it.startsWith("total=") || it.startsWith("items=") ||
                it.startsWith("concurrency=") || it.startsWith("processed=") ||
                it.startsWith("success=") || it.startsWith("skipped=") || it.startsWith("failure=") },
        )
        assertTrue(detail.startsWith("taskId=$taskId\ntype=EDIT_TAGS\nstatus=succeeded\n"), detail)
    }

    @Test
    fun `every item goes to running and then to succeeded and the task reports the totals`() = runBlocking<Unit> {
        val processor = ScriptedProcessor()
        val taskId = newTask(songs = 3)

        runner(processor).run(taskId)

        val items = repository.observeItems(taskId).first()
        assertEquals(listOf(BatchTaskStatus.SUCCEEDED, BatchTaskStatus.SUCCEEDED, BatchTaskStatus.SUCCEEDED),
            items.map { it.status })
        assertEquals(3, processor.startedItems.size, "each file is handed to the processor exactly once")

        val task = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, task.status)
        assertEquals(3, task.current)
        assertEquals(3, task.successCount)
        assertEquals(0, task.failureCount)
        assertEquals(0, task.skippedCount)
        assertNotNull(task.startedAt)
        assertNotNull(task.finishedAt)
        // The current-file column follows the last file that finished, and with three files racing
        // that order is not fixed, so only membership is asserted.
        assertTrue(
            task.currentFile in items.map { it.fileName },
            "currentFile should name one of the processed files, was ${task.currentFile}",
        )

        val entry = log.entries.single()
        assertEquals(AppLogLevel.INFO, entry.level)
        assertEquals("Batch task finished: EDIT_TAGS (processed=3, success=3, skipped=0, failure=0)", entry.message)
        assertTrue(assertNotNull(entry.detail).contains("status=succeeded"), entry.detail)
    }

    @Test
    fun `the result of the processor is written to the item`() = runBlocking<Unit> {
        val processor = ScriptedProcessor { item ->
            BatchTaskProcessResult(
                resultJson = """{"file":"${item.fileName}"}""",
                updatedFilePath = """H:\Music\renamed-${item.fileName}""",
                updatedFileName = "renamed-${item.fileName}",
            )
        }
        val taskId = newTask(songs = 1)

        runner(processor).run(taskId)

        val item = repository.observeItems(taskId).first().single()
        assertEquals("""{"file":"track1.mp3"}""", item.resultJson)
        assertEquals("""H:\Music\renamed-track1.mp3""", item.filePath)
        assertEquals("renamed-track1.mp3", item.fileName)
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status)
    }

    // ------------------------------------------------------------------ skips and failures

    @Test
    fun `a skip and a failure are recorded per file while the task still finishes`() = runBlocking<Unit> {
        // Android finished the task as SUCCEEDED and reported the failures in the summary and the item
        // rows; the item statuses are what a user reads, so they have to survive unchanged.
        val processor = ScriptedProcessor { item ->
            when (item.fileName) {
                "track1.mp3" -> BatchTaskProcessResult()
                "track2.mp3" -> throw BatchTaskSkippedException("No lyrics")
                else -> throw IllegalStateException("磁盘已满")
            }
        }
        val taskId = newTask(songs = 3)

        runner(processor).run(taskId)

        val items = repository.observeItems(taskId).first().sortedBy { it.fileName }
        assertEquals(BatchTaskStatus.SUCCEEDED, items[0].status)
        // A skip's reason is stored in the *result* column, not in the error: the port of the
        // repository had nowhere else to put it, and the task list reads it from there.
        assertEquals(BatchTaskStatus.SKIPPED, items[1].status)
        assertEquals("No lyrics", items[1].resultJson)
        assertEquals(null, items[1].errorMessage)
        assertEquals(BatchTaskStatus.FAILED, items[2].status)
        assertEquals("磁盘已满", items[2].errorMessage)

        val task = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, task.status)
        assertEquals(3, task.current)
        assertEquals(1, task.successCount)
        assertEquals(1, task.skippedCount)
        assertEquals(1, task.failureCount)

        val entry = log.entries.single()
        // A run that touched a file it could not finish is not "info" -- the log screen colours it.
        assertEquals(AppLogLevel.WARNING, entry.level)
        assertEquals("Batch task finished: EDIT_TAGS (processed=3, success=1, skipped=1, failure=1)", entry.message)
        val detail = assertNotNull(entry.detail)
        assertTrue(detail.contains("status=finished_with_errors"), detail)
        assertTrue(detail.contains("SKIPPED track2.mp3: No lyrics"), detail)
        assertTrue(detail.contains("FAILED track3.mp3: 磁盘已满"), detail)
        assertTrue(detail.contains("java.lang.IllegalStateException: 磁盘已满"), "the stack trace is kept: $detail")
    }

    @Test
    fun `an item whose processor result is empty is still a success`() = runBlocking<Unit> {
        // `EditTagsProcessor` returns no result JSON at all; a result is not what makes an item succeed.
        val taskId = newTask(songs = 1)

        runner(ScriptedProcessor()).run(taskId)

        val item = repository.observeItems(taskId).first().single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status)
        assertEquals(null, item.resultJson)
        assertEquals(null, item.errorMessage)
    }

    @Test
    fun `a task type with no registered processor fails instead of staying running`() = runBlocking<Unit> {
        // Android marked the task RUNNING and then looked the processor up, so this left a task that
        // claimed to be running with nobody running it until the next start-up cleaned up the row.
        val taskId = newTask(songs = 2)

        runner(ScriptedProcessor(), registered = false).run(taskId)

        val task = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskStatus.FAILED, task.status)
        assertEquals("No processor registered for EDIT_TAGS", task.errorMessage)
        assertEquals(null, task.startedAt, "the task never started, and the row has to say so")
        assertTrue(
            repository.observeItems(taskId).first().all { it.status == BatchTaskStatus.QUEUED },
            "no file was touched",
        )

        val entry = log.entries.single()
        assertEquals(AppLogLevel.ERROR, entry.level)
        assertEquals("Batch task failed: EDIT_TAGS (no processor registered)", entry.message)
        assertEquals(taskId, entry.relatedId)
        assertTrue(assertNotNull(entry.detail).contains("IllegalArgumentException"), entry.detail)
    }

    @Test
    fun `an unknown or empty task id is ignored`() = runBlocking<Unit> {
        val runner = runner(ScriptedProcessor())

        runner.run("no-such-task")
        runner.run("")

        assertTrue(log.entries.isEmpty(), "nothing was run, so nothing is logged")
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    fun `the configured concurrency is the number of files processed at once`() = runBlocking<Unit> {
        // The clamp is part of the contract: a config written by a hand-edited database, by an older
        // version or by a future screen must not be able to start a hundred tag writes at once.
        val cases = listOf(
            null to 1,
            "{}" to 3,
            """{"concurrency":0}""" to 1,
            """{"concurrency":2}""" to 2,
            """{"concurrency":4}""" to 4,
            """{"concurrency":99}""" to 5,
            "{not json" to 3,
        )

        for ((configJson, expected) in cases) {
            val processor = ScriptedProcessor(work = { delay(60); BatchTaskProcessResult() })
            val taskId = newTask(songs = 6, configJson = configJson)

            runner(processor).run(taskId)

            assertEquals(
                expected,
                processor.maxInFlight.get(),
                "concurrency for config=$configJson should be $expected",
            )
            assertEquals(BatchTaskStatus.SUCCEEDED, assertNotNull(dao.getTask(taskId)).status)
        }
    }

    // ------------------------------------------------------------------ the app log summary

    @Test
    fun `the rename configuration is summarised in the log`() = runBlocking<Unit> {
        val config = RenameFilesTaskConfig(
            renameFormat = "@1 - @2",
            characterMappingRules = listOf(
                CharacterMappingRule(id = "colon", name = "冒号", charMappings = mapOf(":" to "：")),
                CharacterMappingRule(id = "untouched", name = "未改动"),
            ),
        )
        val taskId = newTaskType(configJson = encode(config))

        runner(ScriptedProcessor()).run(taskId)

        assertEquals(
            """
            renameFormat=@1 - @2
            characterMappingRules=2
            customizedMappingRules=冒号
            """.trimIndent(),
            configBlockOf(taskId),
        )
    }

    @Test
    fun `the edit-tags configuration lists the modified fields, the rating and the custom keys`() = runBlocking<Unit> {
        val config = EditTagsTaskConfig(
            title = "新标题",
            artist = "A\r\nB",
            discNumber = "3",
            lyrics = "[00:01.000]一行",
            ratingModified = true,
            rating = 4,
            customFields = listOf(EditTagsCustomField(key = "MOOD", value = "calm")),
            concurrency = 2,
        )
        val taskId = newTaskType(configJson = encode(config))

        runner(ScriptedProcessor()).run(taskId)

        assertEquals(
            """
            concurrency=2
            modifiedFields=title, artist, discNumber, lyrics, rating, customFields
            rating=4
            customFieldKeys=MOOD
            title=新标题
            artist=A\r\nB
            discNumber=3
            lyrics=(modified, 13 chars)
            """.trimIndent(),
            configBlockOf(taskId),
        )
    }

    @Test
    fun `a long configuration value is truncated so one file cannot fill the log`() = runBlocking<Unit> {
        val config = EditTagsTaskConfig(title = "长".repeat(250), concurrency = 1)
        val taskId = newTaskType(configJson = encode(config))

        runner(ScriptedProcessor()).run(taskId)

        val block = configBlockOf(taskId)
        assertTrue(block.contains("title=${"长".repeat(200)}... (250 chars)"), block)
    }

    @Test
    fun `the lyrics-format configuration is summarised in the log`() = runBlocking<Unit> {
        val config = LyricsFormatConfig(
            targetFormat = LyricFormat.TTML,
            concurrency = 3,
            formatLineOrder = false,
            removeTagLines = true,
            removeEmptyLines = true,
        )
        val taskId = newTaskType(configJson = encode(config))

        runner(ScriptedProcessor()).run(taskId)

        assertEquals(
            """
            targetFormat=TTML
            concurrency=3
            operation=CONVERT
            twoColumnMapping=LyricsColumnMapping(sourceCount=2, order=[0, 1])
            threeColumnMapping=LyricsColumnMapping(sourceCount=3, order=[0, 1, 2])
            formatLineOrder=false
            removeTagLines=true
            removeEmptyLines=true
            """.trimIndent(),
            configBlockOf(taskId),
        )
    }

    @Test
    fun `a configuration that cannot be parsed is reported without failing the task`() = runBlocking<Unit> {
        // The config is written by a screen, so it should always parse; a task whose config is
        // unreadable still has to run (its processor has its own opinion) and the log has to say why
        // the summary is missing rather than the run failing.
        val taskId = newTaskType(configJson = "{not json", songs = 1)

        runner(ScriptedProcessor()).run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, assertNotNull(dao.getTask(taskId)).status)
        val block = configBlockOf(taskId)
        assertTrue(block.startsWith("configParseError="), block)
        assertTrue(block.contains("rawConfig={not json"), block)
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    fun `a cancelled run finishes the task as cancelled and does not claim the interrupted file`() =
        runBlocking<Unit> {
            val processor = ScriptedProcessor(work = { processorGate.await(); BatchTaskProcessResult() })
            val taskId = newTask(songs = 3)
            val runner = runner(processor)

            val job = launch(Dispatchers.Default) { runner.run(taskId) }
            val interruptedItemId = processor.started.receive()
            job.cancelAndJoin()

            val task = assertNotNull(dao.getTask(taskId))
            assertEquals(BatchTaskStatus.CANCELLED, task.status)
            assertEquals(null, task.errorMessage, "cancelling is not a failure")
            assertNotNull(task.finishedAt)

            val items = repository.observeItems(taskId).first()
            val interrupted = items.single { it.itemId == interruptedItemId }
            // Measured rather than assumed: cancelling while a file is in flight ends that item as
            // FAILED carrying the cancellation as its reason, and leaves the files that never started
            // QUEUED. The ported worker's per-item `catch (e: Exception)` does not special-case the
            // cancellation, so the item is booked like any other failure -- which is also why the
            // summary below counts it as one.
            //
            // Worth knowing before the UI batch: a re-requested task only picks up QUEUED and RUNNING
            // items, so pressing the button again after a cancel does not retry the interrupted file.
            // Start-up recovery, not a second request, is what turns such rows FAILED.
            assertEquals(BatchTaskStatus.FAILED, interrupted.status)
            assertTrue(
                interrupted.errorMessage!!.lowercase().contains("cancel"),
                "the item has to say the run was cancelled, not that the file was bad: ${interrupted.errorMessage}",
            )
            assertTrue(
                items.filter { it.itemId != interruptedItemId }.all { it.status == BatchTaskStatus.QUEUED },
                "no other file was touched: ${items.map { it.itemId to it.status }}",
            )

            val entry = log.entries.single()
            assertEquals(AppLogLevel.WARNING, entry.level)
            assertEquals(
                "Batch task cancelled: EDIT_TAGS (processed=1, success=0, skipped=0, failure=1)",
                entry.message,
            )
            val detail = assertNotNull(entry.detail)
            assertTrue(detail.contains("status=cancelled"), detail)
            assertTrue(detail.contains("items=3"), "the whole batch is reported, not just the part that ran: $detail")
        }

    @Test
    fun `a cancelled run keeps the files that had already finished`() = runBlocking<Unit> {
        // The first file that arrives is let through, every later one hangs: cancelling then lands in
        // the middle of the batch, with one file really finished and one really in flight.
        val firstArrival = AtomicReference<String?>(null)
        val firstFinished = Channel<String>(Channel.UNLIMITED)
        val processor = ScriptedProcessor { item ->
            if (firstArrival.compareAndSet(null, item.itemId)) {
                firstFinished.trySend(item.itemId)
            } else {
                processorGate.await()
            }
            BatchTaskProcessResult()
        }
        val taskId = newTask(songs = 2, configJson = """{"concurrency":1}""")
        val runner = runner(processor)

        val job = launch(Dispatchers.Default) { runner.run(taskId) }
        val first = firstFinished.receive()
        // `started` also holds the first file, so the second distinct id is the one picked up next.
        var second = processor.started.receive()
        while (second == first) second = processor.started.receive()
        job.cancelAndJoin()

        val items = repository.observeItems(taskId).first()
        assertEquals(BatchTaskStatus.SUCCEEDED, items.single { it.itemId == first }.status)
        assertEquals(BatchTaskStatus.FAILED, items.single { it.itemId == second }.status)
        // The file that finished stays finished, the interrupted one is counted as a failure, and
        // both are counted as processed -- `processed` counts every item that reached the processor,
        // not only the ones that came back.
        assertEquals(
            "Batch task cancelled: EDIT_TAGS (processed=2, success=1, skipped=0, failure=1)",
            log.entries.single().message,
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The gate the cancellation tests hang on.
     *
     * It is deliberately never completed: awaiting it suspends until the calling coroutine is
     * cancelled, which is exactly the interruption those tests are about. No test completes it, and a
     * test that does not await it is unaffected.
     */
    private val processorGate = CompletableDeferred<Unit>()

    private class ScriptedProcessor(
        private val work: suspend (BatchTaskItemEntity) -> BatchTaskProcessResult = { BatchTaskProcessResult() },
    ) : BatchTaskProcessor {

        /** Every item handed to [process], in start order -- one entry per call, not per success. */
        val startedItems: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        /** The same starts as a channel, so a test can wait for one without polling. */
        val started = Channel<String>(Channel.UNLIMITED)

        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        override suspend fun process(
            task: BatchTaskEntity,
            item: BatchTaskItemEntity,
            onProgress: suspend (Float) -> Unit,
        ): BatchTaskProcessResult {
            startedItems += item.itemId
            started.trySend(item.itemId)
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                return work(item)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun runner(processor: BatchTaskProcessor, registered: Boolean = true): BatchTaskRunner =
        BatchTaskRunner(
            taskRepository = repository,
            processorFactory = BatchTaskProcessorFactory(
                // Scripted for every type: the log-summary tests create tasks of three different
                // types, and the runner has to get past the factory lookup to reach the summariser.
                if (registered) BatchTaskType.entries.associateWith { processor } else emptyMap()
            ),
            appLogRepository = log,
        )

    private suspend fun newTask(songs: Int = 3, configJson: String? = null): String =
        repository.createTask(
            BatchTaskType.EDIT_TAGS,
            (1..songs).map { library.song(path = """H:\Music\track$it.mp3""", folderId = 1L) },
            configJson,
        )

    private var nextTypedTask = 0

    /** Creates a task of the type the *config* belongs to, so the summary under test is the one used. */
    private suspend fun newTaskType(configJson: String, songs: Int = 1): String {
        nextTypedTask++
        val type = when {
            configJson.contains("renameFormat") -> BatchTaskType.RENAME_FILES
            configJson.contains("targetFormat") -> BatchTaskType.CONVERT_LYRICS_FORMAT
            else -> BatchTaskType.EDIT_TAGS
        }
        return repository.createTask(
            type,
            (1..songs).map { library.song(path = """H:\Music\typed$nextTypedTask-$it.mp3""", folderId = 1L) },
            configJson,
        )
    }

    private val json = Json { encodeDefaults = true }

    private fun encode(config: Any): String = when (config) {
        is RenameFilesTaskConfig -> json.encodeToString(RenameFilesTaskConfig.serializer(), config)
        is EditTagsTaskConfig -> json.encodeToString(EditTagsTaskConfig.serializer(), config)
        is LyricsFormatConfig -> json.encodeToString(LyricsFormatConfig.serializer(), config)
        else -> error("no serializer for ${config::class}")
    }

    /**
     * The `configuration:` block of the log detail, with the two-space indent of the summary removed.
     *
     * The block is the one thing in the detail whose exact text is stable -- the timestamps and the
     * duration around it are not -- so it is compared whole rather than line by line.
     */
    private fun configBlockOf(taskId: String): String {
        val detail = assertNotNull(
            log.entries.singleOrNull { it.relatedId == taskId }?.detail,
            "expected one log entry for task $taskId",
        )
        return detail.substringAfter("configuration:\n")
            .substringBefore("\n\nstartedAt=")
            .lines()
            .joinToString("\n") { it.removePrefix("  ") }
    }
}
