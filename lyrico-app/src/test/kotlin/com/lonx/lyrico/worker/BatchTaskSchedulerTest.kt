package com.lonx.lyrico.worker

import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.BatchTaskRepositoryImpl
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.worker.processor.BatchTaskProcessResult
import com.lonx.lyrico.worker.processor.BatchTaskProcessor
import com.lonx.lyrico.worker.processor.BatchTaskProcessorFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The queue that starts and stops batch tasks -- the ported `BatchTaskScheduler`.
 *
 * On Android WorkManager held the job: unique work named after the task id with `ExistingWorkPolicy
 * .KEEP` meant a duplicate request was dropped, and cancelling by tag stopped the job. Here the queue
 * is a map of the coroutines this scheduler launched, so what has to hold is the same three things:
 * a duplicate request while a task is running starts nothing, cancelling stops that run *and* lets the
 * task be requested again afterwards, and the job id on the task row belongs to a run that really
 * happened (Android overwrote it even for an ignored request).
 *
 * The runner is real -- it is the thing being started -- and so are the database rows it writes. Only
 * the processor is scripted, because the tests need a batch that hangs until they say otherwise.
 */
class BatchTaskSchedulerTest {

    private lateinit var library: TestLibrary
    private lateinit var repository: BatchTaskRepository
    private lateinit var log: RecordingAppLogRepository
    private lateinit var scope: CoroutineScope

    private val dao get() = library.database.batchTaskDao()

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        repository = BatchTaskRepositoryImpl(batchTaskDao = dao)
        log = RecordingAppLogRepository()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        library.close()
    }

    @Test
    fun `a duplicate request for a running task is ignored and does not rewrite the job id`() = runBlocking<Unit> {
        val processor = GatedProcessor(blocking = true)
        val scheduler = scheduler(processor)
        val taskId = newTask(songs = 1)

        scheduler.enqueue(taskId)
        processor.started.receive()
        val jobIdOfTheRunningTask = workId(taskId)
        assertNotNull(jobIdOfTheRunningTask)

        scheduler.enqueue(taskId)
        scheduler.enqueue(taskId)

        assertEquals(
            jobIdOfTheRunningTask,
            workId(taskId),
            "an ignored request must not claim a job id for a run that never started",
        )
        assertEquals(1, processor.startCount.get(), "the second and third requests started nothing")
        assertEquals(BatchTaskStatus.RUNNING, assertNotNull(dao.getTask(taskId)).status)

        processor.gate.complete(Unit)
        awaitStatus(taskId, BatchTaskStatus.SUCCEEDED)
        assertEquals(1, processor.startCount.get())
    }

    @Test
    fun `a request for a task that already finished is accepted and runs the task again`() = runBlocking<Unit> {
        // Nothing is pending any more, so the second run is a no-op over the files -- but it *is* a
        // run: it gets its own job id and writes its own log entry. That is what makes a re-request
        // after a crash observable rather than silently swallowed.
        val processor = GatedProcessor(blocking = false)
        val scheduler = scheduler(processor)
        val taskId = newTask(songs = 1)

        scheduler.enqueue(taskId)
        awaitStatus(taskId, BatchTaskStatus.SUCCEEDED)
        val firstJobId = workId(taskId)
        drain(processor)
        assertEquals(1, log.entries.size)

        // The scheduler clears its entry in the job's `finally`, which runs a moment after the last
        // row write the test just observed, so the request is retried until it is accepted. The new
        // job id is the signal that it was.
        var secondJobId: String? = null
        withTimeout(10_000) {
            while (secondJobId == null) {
                scheduler.enqueue(taskId)
                val now = workId(taskId)
                if (now != firstJobId) secondJobId = now else delay(20)
            }
        }
        assertNotNull(secondJobId)
        withTimeout(10_000) { while (log.entries.size < 2) delay(10) }

        assertEquals(
            "Batch task finished: EDIT_TAGS (0/1 processed)",
            log.entries.last().message,
            "the second run found every file already done and still finished the task",
        )
        assertEquals(1, processor.startCount.get(), "no file was pending, so none was processed again")
        assertEquals(BatchTaskStatus.SUCCEEDED, assertNotNull(dao.getTask(taskId)).status)
    }

    @Test
    fun `cancelling a task stops its run and lets the same task be requested again`() = runBlocking<Unit> {
        val processor = GatedProcessor(blocking = true)
        val scheduler = scheduler(processor)
        val taskId = newTask(songs = 2)

        scheduler.enqueue(taskId)
        processor.started.receive()
        scheduler.cancel(taskId)
        awaitStatus(taskId, BatchTaskStatus.CANCELLED)
        drain(processor)

        processor.blocking = false
        scheduler.enqueue(taskId)
        assertNotNull(withTimeout(10_000) { processor.started.receive() })
        awaitStatus(taskId, BatchTaskStatus.SUCCEEDED)

        // The cancelled run booked the file it was writing as FAILED, so the second run has only the
        // file that never started to do -- cancelling costs the user that one file, and re-requesting
        // the task does not get it back. That is the ported pending-item rule (QUEUED and RUNNING),
        // not something the scheduler invents.
        val items = repository.observeItems(taskId).first()
        assertEquals(2, items.size)
        assertEquals(1, items.count { it.status == BatchTaskStatus.FAILED })
        assertEquals(1, items.count { it.status == BatchTaskStatus.SUCCEEDED })
        assertEquals(2, processor.startCount.get(), "one file before the cancellation, one after it")
        assertTrue(log.entries.first().message.startsWith("Batch task cancelled"), log.entries.first().message)
        assertEquals(BatchTaskStatus.SUCCEEDED, assertNotNull(dao.getTask(taskId)).status)
    }

    @Test
    fun `cancelling a task that is not running does nothing`() = runBlocking<Unit> {
        val scheduler = scheduler(GatedProcessor(blocking = false))
        val taskId = newTask(songs = 1)

        scheduler.cancel(taskId)
        scheduler.cancel("no-such-task")

        val task = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskStatus.QUEUED, task.status)
        assertNull(task.workId, "nothing ran, so nothing claimed a job id")
        assertTrue(log.entries.isEmpty())
    }

    @Test
    fun `two tasks are queued independently`() = runBlocking<Unit> {
        val processor = GatedProcessor(blocking = false)
        val scheduler = scheduler(processor)
        val first = newTask(songs = 1)
        val second = newTask(songs = 1)

        scheduler.enqueue(first)
        scheduler.enqueue(second)
        awaitStatus(first, BatchTaskStatus.SUCCEEDED)
        awaitStatus(second, BatchTaskStatus.SUCCEEDED)
        // The log entry is the last thing a run writes, so waiting for both is waiting for both runs.
        withTimeout(10_000) { while (log.entries.size < 2) delay(10) }

        assertTrue(workId(first) != workId(second))
        assertEquals(2, processor.startCount.get())
        assertEquals(2, log.entries.size)
    }

    // ------------------------------------------------------------------ helpers

    private class GatedProcessor(
        @Volatile var blocking: Boolean,
    ) : BatchTaskProcessor {

        val gate = CompletableDeferred<Unit>()
        val started = Channel<String>(Channel.UNLIMITED)
        val startCount = AtomicInteger(0)

        override suspend fun process(
            task: BatchTaskEntity,
            item: BatchTaskItemEntity,
            onProgress: suspend (Float) -> Unit,
        ): BatchTaskProcessResult {
            startCount.incrementAndGet()
            started.trySend(item.itemId)
            if (blocking) gate.await()
            return BatchTaskProcessResult()
        }
    }

    private fun scheduler(processor: BatchTaskProcessor): BatchTaskScheduler = BatchTaskScheduler(
        runner = BatchTaskRunner(
            taskRepository = repository,
            processorFactory = BatchTaskProcessorFactory(
                BatchTaskType.entries.associateWith { processor }
            ),
            appLogRepository = log,
        ),
        taskRepository = repository,
        scope = scope,
    )

    private suspend fun newTask(songs: Int = 1): String =
        repository.createTask(
            BatchTaskType.EDIT_TAGS,
            (1..songs).map { library.song(path = """H:\Music\track$it.mp3""", folderId = 1L) },
            configJson = null,
        )

    private suspend fun workId(taskId: String): String? = dao.getTask(taskId)?.workId

    private suspend fun awaitStatus(taskId: String, status: BatchTaskStatus) {
        withTimeout(10_000) { repository.observeTask(taskId).first { it?.status == status } }
    }

    /** Throws away the start signals of a run that has already finished. */
    private fun drain(processor: GatedProcessor) {
        while (processor.started.tryReceive().isSuccess) {
            // nothing to do: the tokens are only interesting while a run is in flight
        }
    }
}
