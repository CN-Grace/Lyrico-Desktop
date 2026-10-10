package com.lonx.lyrico.worker.processor

import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity

interface BatchTaskProcessor {
    suspend fun process(
        task: BatchTaskEntity,
        item: BatchTaskItemEntity,
        onProgress: suspend (Float) -> Unit = {}
    ): BatchTaskProcessResult
}

data class BatchTaskProcessResult(
    val resultJson: String? = null,
    val updatedFilePath: String? = null,
    val updatedFileName: String? = null
)

class BatchTaskSkippedException(message: String?) : Exception(message)
