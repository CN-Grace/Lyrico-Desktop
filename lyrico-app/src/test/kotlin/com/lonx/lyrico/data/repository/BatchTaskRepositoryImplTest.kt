package com.lonx.lyrico.data.repository

import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.support.TestLibrary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The batch task bookkeeping, against a real database.
 *
 * A batch task is the only long-running work on desktop that outlives the process, so the two things
 * that must hold are: the task and its per-file rows are written together (a task with no items can
 * never be resumed), and the progress counters are derived from the item rows rather than incremented
 * in place (a counter incremented per file drifts as soon as one file is retried). Both are asserted
 * against the rows in SQLite, not against the returned values.
 *
 * The `workId` column is WorkManager's job id on Android; here it is the desktop queue's own job id.
 * It is kept as-is so the schema stays byte-compatible with an Android `lyrico.db`.
 */
class BatchTaskRepositoryImplTest {

    private lateinit var library: TestLibrary
    private lateinit var repository: BatchTaskRepository

    private val dao get() = library.database.batchTaskDao()

    @BeforeTest
    fun setUp() {
        library = TestLibrary()
        repository = BatchTaskRepositoryImpl(batchTaskDao = dao)
    }

    @AfterTest
    fun tearDown() {
        library.close()
    }

    @Test
    fun `creating a task writes the task and one item per song`() = runBlocking<Unit> {
        val songs = (1..3).map { song("""H:\Music\track$it.mp3""") }

        val taskId = repository.createTask(BatchTaskType.EDIT_TAGS, songs, configJson = """{"a":1}""")

        val task = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskType.EDIT_TAGS, task.type)
        assertEquals(BatchTaskStatus.QUEUED, task.status)
        assertEquals(3, task.total)
        assertEquals(0, task.current)
        assertEquals(0, task.successCount)
        assertEquals(0, task.failureCount)
        assertEquals(0, task.skippedCount)
        assertNull(task.currentFile)
        assertNull(task.workId)
        assertNull(task.startedAt)
        assertNull(task.finishedAt)
        assertEquals("""{"a":1}""", task.configJson)

        val items = dao.getItemsByStatus(taskId, BatchTaskStatus.QUEUED)
        assertEquals(3, items.size)
        assertEquals(
            songs.map { it.uri },
            items.sortedBy { it.itemId }.map { it.songUri },
            "the items have to carry the songs in order, or a resume cannot tell which file is which",
        )
        assertEquals(listOf("$taskId-0", "$taskId-1", "$taskId-2"), items.map { it.itemId }.sorted())
        assertTrue(items.all { it.taskId == taskId && it.status == BatchTaskStatus.QUEUED })
        assertTrue(items.all { it.progress == null && it.resultJson == null && it.errorMessage == null })
    }

    @Test
    fun `creating a task with no songs still writes the task`() = runBlocking<Unit> {
        // An empty selection is a legal (if pointless) batch; it must not leave a half-written row.
        val taskId = repository.createTask(BatchTaskType.MATCH_LYRICS, emptyList(), configJson = null)

        val task = assertNotNull(dao.getTask(taskId))
        assertEquals(0, task.total)
        assertTrue(dao.getItemsByStatus(taskId, BatchTaskStatus.QUEUED).isEmpty())
    }

    @Test
    fun `two tasks made from the same songs do not share items`() = runBlocking<Unit> {
        val songs = listOf(song("""H:\Music\a.mp3"""))

        val first = repository.createTask(BatchTaskType.EDIT_TAGS, songs, null)
        val second = repository.createTask(BatchTaskType.EDIT_TAGS, songs, null)

        assertTrue(first != second)
        assertEquals(listOf("$first-0"), dao.getItemsByStatus(first, BatchTaskStatus.QUEUED).map { it.itemId })
        assertEquals(listOf("$second-0"), dao.getItemsByStatus(second, BatchTaskStatus.QUEUED).map { it.itemId })
    }

    @Test
    fun `a started task records when it started and a finished one when it finished`() = runBlocking<Unit> {
        val taskId = newTask()

        repository.markRunning(taskId)

        val running = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskStatus.RUNNING, running.status)
        assertNotNull(running.startedAt)
        assertNull(running.finishedAt)

        repository.markSucceeded(taskId)

        val succeeded = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, succeeded.status)
        assertNotNull(succeeded.finishedAt)
        assertEquals(running.startedAt, succeeded.startedAt, "the start time is historical, not current")
        assertNull(succeeded.errorMessage)
    }

    @Test
    fun `marking a task failed records the reason and clearing it on success`() = runBlocking<Unit> {
        val taskId = newTask()
        repository.markRunning(taskId)

        repository.markFailed(taskId, "磁盘已满")

        val failed = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskStatus.FAILED, failed.status)
        assertEquals("磁盘已满", failed.errorMessage)

        repository.markSucceeded(taskId)

        val succeeded = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskStatus.SUCCEEDED, succeeded.status)
        assertNull(succeeded.errorMessage, "a resolved task must not keep showing a stale error")
    }

    @Test
    fun `a cancelled task is finished without an error`() = runBlocking<Unit> {
        val taskId = newTask()
        repository.markFailed(taskId, "boom")

        repository.markCancelled(taskId)

        val cancelled = assertNotNull(dao.getTask(taskId))
        assertEquals(BatchTaskStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.errorMessage)
        assertNotNull(cancelled.finishedAt)
    }

    @Test
    fun `an item records its result on success and its reason on failure`() = runBlocking<Unit> {
        val taskId = newTask()
        val itemId = "$taskId-0"

        repository.markItemSucceeded(itemId, resultJson = """{"title":"matched"}""")
        val succeeded = assertNotNull(dao.getTask(taskId)) // touch the task so both rows are read after
        assertEquals(BatchTaskStatus.QUEUED, succeeded.status, "an item finishing does not finish the task")

        val item = dao.getItemsByStatus(taskId, BatchTaskStatus.SUCCEEDED).single()
        assertEquals("""{"title":"matched"}""", item.resultJson)
        assertNull(item.errorMessage)

        repository.markItemFailed(itemId, error = "文件已被占用")

        val failed = dao.getItemsByStatus(taskId, BatchTaskStatus.FAILED).single()
        assertEquals("文件已被占用", failed.errorMessage)
        assertNull(failed.resultJson, "a failed item must not keep the result of its previous attempt")
    }

    @Test
    fun `a skipped item keeps its result and reports no error`() = runBlocking<Unit> {
        val taskId = newTask()

        repository.markItemSkipped("$taskId-0", resultJson = """{"reason":"already matched"}""")

        val item = dao.getItemsByStatus(taskId, BatchTaskStatus.SKIPPED).single()
        assertEquals("""{"reason":"already matched"}""", item.resultJson)
        assertNull(item.errorMessage)
    }

    @Test
    fun `an item keeps its own progress between status changes`() = runBlocking<Unit> {
        val taskId = newTask()
        val itemId = "$taskId-0"

        repository.markItemRunning(itemId)
        repository.updateItemProgress(itemId, 0.42f)

        val running = dao.getItemsByStatus(taskId, BatchTaskStatus.RUNNING).single()
        assertEquals(BatchTaskStatus.RUNNING, running.status)
        assertEquals(0.42f, running.progress)

        repository.markItemSucceeded(itemId, null)

        assertEquals(0.42f, dao.getItemsByStatus(taskId, BatchTaskStatus.SUCCEEDED).single().progress)
    }

    @Test
    fun `an item can be pointed at the file it was renamed to`() = runBlocking<Unit> {
        val taskId = newTask()

        repository.updateItemFileInfo("$taskId-0", """H:\Music\Renamed.mp3""", "Renamed.mp3")

        val item = dao.getItemsByStatus(taskId, BatchTaskStatus.QUEUED).single()
        assertEquals("""H:\Music\Renamed.mp3""", item.filePath)
        assertEquals("Renamed.mp3", item.fileName)
    }

    @Test
    fun `progress is the count of finished items, in every terminal state`() = runBlocking<Unit> {
        val taskId = repository.createTask(BatchTaskType.EDIT_TAGS, (1..4).map { song("""H:\Music\t$it.mp3""") }, null)
        repository.markRunning(taskId)
        repository.markItemSucceeded("$taskId-0", null)
        repository.markItemFailed("$taskId-1", "nope")
        repository.markItemSkipped("$taskId-2", null)

        repository.updateProgressFromItems(taskId, currentFile = """H:\Music\t3.mp3""")

        val task = assertNotNull(dao.getTask(taskId))
        assertEquals(3, task.current, "succeeded + failed + skipped")
        assertEquals(1, task.successCount)
        assertEquals(1, task.failureCount)
        assertEquals(1, task.skippedCount)
        assertEquals("""H:\Music\t3.mp3""", task.currentFile)
    }

    @Test
    fun `progress does not count an item that is still queued or running`() = runBlocking<Unit> {
        val taskId = repository.createTask(BatchTaskType.EDIT_TAGS, (1..2).map { song("""H:\Music\t$it.mp3""") }, null)
        repository.markItemRunning("$taskId-0")

        repository.updateProgressFromItems(taskId, currentFile = null)

        val task = assertNotNull(dao.getTask(taskId))
        assertEquals(0, task.current)
        assertNull(task.currentFile, "clearing the current file has to be possible")
    }

    @Test
    fun `reporting progress for a task that does not exist does nothing`() = runBlocking<Unit> {
        repository.updateProgressFromItems("no-such-task", currentFile = "x")

        assertNull(dao.getTask("no-such-task"))
    }

    @Test
    fun `the pending items are the ones a resume still has to do`() = runBlocking<Unit> {
        val taskId = repository.createTask(BatchTaskType.EDIT_TAGS, (1..4).map { song("""H:\Music\t$it.mp3""") }, null)
        repository.markItemSucceeded("$taskId-0", null)
        repository.markItemRunning("$taskId-1")
        repository.markItemFailed("$taskId-2", "nope")

        val pending = repository.getPendingItems(taskId)

        assertEquals(listOf("$taskId-1", "$taskId-3"), pending.map { it.itemId }.sorted())
    }

    @Test
    fun `a task that was running when the app last exited is failed on the next start`() = runBlocking<Unit> {
        // On desktop there is no WorkManager to resume the job: a RUNNING row after a restart means the
        // previous process died, and it has to come back as FAILED rather than as a task that never ends.
        val running = newTask()
        repository.markRunning(running)
        val queued = newTask()

        repository.markOrphanedTasksFailed()

        val failed = assertNotNull(dao.getTask(running))
        assertEquals(BatchTaskStatus.FAILED, failed.status)
        assertEquals("Task interrupted by system", failed.errorMessage)
        assertEquals(BatchTaskStatus.FAILED, assertNotNull(dao.getTask(queued)).status)
        assertTrue(repository.getOrphanedRunningTasks().isEmpty())
    }

    @Test
    fun `recovering orphaned tasks leaves finished ones alone`() = runBlocking<Unit> {
        val done = newTask()
        repository.markRunning(done)
        repository.markSucceeded(done)

        repository.markOrphanedTasksFailed()

        assertEquals(BatchTaskStatus.SUCCEEDED, assertNotNull(dao.getTask(done)).status)
        assertNull(assertNotNull(dao.getTask(done)).errorMessage)
    }

    @Test
    fun `the job id of the desktop queue is stored on the task`() = runBlocking<Unit> {
        val taskId = newTask()

        repository.bindWorkId(taskId, "queue-job-7")

        assertEquals("queue-job-7", assertNotNull(dao.getTask(taskId)).workId)
    }

    @Test
    fun `a running task of a kind can be looked up so a second one is not started`() = runBlocking<Unit> {
        val running = repository.createTask(BatchTaskType.SCAN_REPLAY_GAIN, listOf(song("""H:\Music\a.mp3""")), null)
        repository.markRunning(running)
        // `createdAt` is a millisecond timestamp, so two tasks made in the same millisecond have no
        // defined order; the lookup is only meaningful once they differ.
        Thread.sleep(5L)
        val queued = repository.createTask(BatchTaskType.SCAN_REPLAY_GAIN, listOf(song("""H:\Music\b.mp3""")), null)
        repository.createTask(BatchTaskType.MATCH_LYRICS, listOf(song("""H:\Music\c.mp3""")), null)

        val found = assertNotNull(repository.getRunningTaskByType(BatchTaskType.SCAN_REPLAY_GAIN))

        assertEquals(queued, found.taskId, "the newest queued-or-running task of that type is the one")
        assertNull(repository.getRunningTaskByType(BatchTaskType.EXPORT_LYRICS))
    }

    @Test
    fun `deleting a task deletes its items with it`() = runBlocking<Unit> {
        val taskId = repository.createTask(BatchTaskType.EDIT_TAGS, (1..3).map { song("""H:\Music\t$it.mp3""") }, null)

        repository.deleteTask(taskId)

        assertNull(dao.getTask(taskId))
        assertTrue(dao.getItemsByStatus(taskId, BatchTaskStatus.QUEUED).isEmpty())
    }

    @Test
    fun `deleting several tasks deletes their items with them`() = runBlocking<Unit> {
        val first = repository.createTask(BatchTaskType.EDIT_TAGS, listOf(song("""H:\Music\a.mp3""")), null)
        val second = repository.createTask(BatchTaskType.EDIT_TAGS, listOf(song("""H:\Music\b.mp3""")), null)
        val kept = repository.createTask(BatchTaskType.EDIT_TAGS, listOf(song("""H:\Music\c.mp3""")), null)

        repository.deleteTasks(listOf(first, second))

        assertNull(dao.getTask(first))
        assertNull(dao.getTask(second))
        assertNotNull(dao.getTask(kept))
        assertEquals(1, dao.getItemsByStatus(kept, BatchTaskStatus.QUEUED).size)
    }

    @Test
    fun `deleting no tasks changes nothing`() = runBlocking<Unit> {
        val taskId = newTask()

        repository.deleteTasks(emptyList())

        assertNotNull(dao.getTask(taskId))
    }

    @Test
    fun `clearing the history keeps the tasks that are still going`() = runBlocking<Unit> {
        val running = repository.createTask(BatchTaskType.EDIT_TAGS, listOf(song("""H:\Music\a.mp3""")), null)
        repository.markRunning(running)
        val queued = newTask()
        val succeeded = newTask()
        repository.markSucceeded(succeeded)
        val failed = newTask()
        repository.markFailed(failed, "boom")
        val cancelled = newTask()
        repository.markCancelled(cancelled)

        repository.clearFinishedTasks()

        assertNotNull(dao.getTask(running), "a running task is live work, not history")
        assertNotNull(dao.getTask(queued))
        assertNull(dao.getTask(succeeded))
        assertNull(dao.getTask(failed))
        assertNull(dao.getTask(cancelled))
        assertTrue(dao.getItemsByStatus(succeeded, BatchTaskStatus.QUEUED).isEmpty())
        assertEquals(1, dao.getItemsByStatus(running, BatchTaskStatus.QUEUED).size)
    }

    @Test
    fun `the task list is newest first and the items of a task are listed together`() = runBlocking<Unit> {
        val first = repository.createTask(BatchTaskType.MATCH_LYRICS, listOf(song("""H:\Music\a.mp3""")), null)
        Thread.sleep(5L)
        val second = repository.createTask(BatchTaskType.MATCH_COVER, listOf(song("""H:\Music\b.mp3""")), null)

        assertEquals(listOf(second, first), repository.observeTasks().first().map { it.taskId })
        assertEquals(listOf("$first-0"), repository.observeItems(first).first().map { it.itemId })
        assertEquals(second, assertNotNull(repository.observeTask(second).first()).taskId)
        assertNull(repository.observeTask("no-such-task").first())
    }

    private suspend fun newTask(): String =
        repository.createTask(BatchTaskType.EDIT_TAGS, listOf(song("""H:\Music\a.mp3""")), null)

    private var nextSong = 0

    private fun song(path: String): com.lonx.lyrico.data.model.entity.SongEntity {
        nextSong++
        return library.song(
            path = path,
            title = "Track $nextSong",
            folderId = 1L,
        )
    }
}
