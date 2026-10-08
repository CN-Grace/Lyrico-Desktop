package com.lonx.lyrico.data.model

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.batch_task_status_cancelled
import com.lonx.lyrico.resources.batch_task_status_failed
import com.lonx.lyrico.resources.batch_task_status_queued
import com.lonx.lyrico.resources.batch_task_status_running
import com.lonx.lyrico.resources.batch_task_status_skipped
import com.lonx.lyrico.resources.batch_task_status_succeeded
import org.jetbrains.compose.resources.StringResource

enum class BatchTaskStatus(
    val labelRes: StringResource
) {
    QUEUED(Res.string.batch_task_status_queued),
    RUNNING(Res.string.batch_task_status_running),
    SUCCEEDED(Res.string.batch_task_status_succeeded),
    FAILED(Res.string.batch_task_status_failed),
    SKIPPED(Res.string.batch_task_status_skipped),
    CANCELLED(Res.string.batch_task_status_cancelled)
}
