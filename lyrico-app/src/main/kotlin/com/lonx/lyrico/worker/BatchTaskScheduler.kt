package com.lonx.lyrico.worker

import com.lonx.lyrico.data.repository.BatchTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Starts and stops batch tasks: the desktop counterpart of Android's `BatchTaskScheduler`.
 *
 * Android handed the task off to WorkManager as unique work named `batch_task_<taskId>` with
 * `ExistingWorkPolicy.KEEP`, and cancelled it by tag. WorkManager owns the coroutine in which the
 * task runs, so on the desktop the equivalent is a [ConcurrentHashMap] of the jobs this scheduler
 * launched in an application-scoped [CoroutineScope], keyed by task id:
 *
 *  * `enqueue` keeps the KEEP semantics -- a task id whose job is still active is left alone, so a
 *    second press of a button that already started a task does not start it twice.
 *  * `cancel` cancels that job. The runner reacts to the cancellation of its own coroutine the way
 *    the Android worker reacted to WorkManager's stop flag: it finishes the final bookkeeping
 *    inside `NonCancellable` and marks the task CANCELLED.
 *
 * One deliberate difference: Android called `bindWorkId` *before* the KEEP check, so an ignored
 * duplicate request still overwrote `workId` with a work id that never ran. Here the column is
 * written only when a job actually starts. Nothing reads `workId` any more -- it is kept for
 * database compatibility with Android -- so this only makes the column say what it means.
 */
class BatchTaskScheduler(
    private val runner: BatchTaskRunner,
    private val taskRepository: BatchTaskRepository,
    private val scope: CoroutineScope
) {

    private val jobs = ConcurrentHashMap<String, Job>()

    suspend fun enqueue(taskId: String) {
        if (jobs[taskId]?.isActive == true) return

        taskRepository.bindWorkId(taskId, UUID.randomUUID().toString())

        val job = scope.launch {
            try {
                runner.run(taskId)
            } finally {
                // Two-argument remove: if this job already finished before the map was updated by
                // `enqueue` below, the entry may hold a *newer* job for the same task id, and
                // removing that one would let a duplicate task start while it is still running.
                jobs.remove(taskId, currentCoroutineContext()[Job])
            }
        }
        jobs[taskId] = job
    }

    fun cancel(taskId: String) {
        jobs.remove(taskId)?.cancel()
    }
}
