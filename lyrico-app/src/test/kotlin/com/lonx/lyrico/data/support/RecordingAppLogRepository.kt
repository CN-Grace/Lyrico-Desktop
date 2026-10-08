package com.lonx.lyrico.data.support

import com.lonx.lyrico.data.model.entity.AppLogEntity
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.repository.AppLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * An [AppLogRepository] that records logged exceptions instead of writing them anywhere.
 *
 * The logging repository is a dependency of most of the data layer, but a test that is not about
 * logging should not need a database and a retention policy to construct its subject. Exceptions are
 * kept so a test can still assert that a failure was reported rather than swallowed.
 */
class RecordingAppLogRepository : AppLogRepository {
    val exceptions = mutableListOf<RecordedException>()

    override fun observeLatest(limit: Int): Flow<List<AppLogEntity>> = flowOf(emptyList())

    override fun observeByRelatedId(relatedId: String): Flow<List<AppLogEntity>> = flowOf(emptyList())

    override suspend fun getLatest(limit: Int): List<AppLogEntity> = emptyList()

    override suspend fun getByIds(ids: List<Long>): List<AppLogEntity> = emptyList()

    override suspend fun exportText(limit: Int): String = ""

    override suspend fun exportText(ids: List<Long>): String = ""

    override suspend fun log(
        level: AppLogLevel,
        type: AppLogType,
        tag: String,
        message: String,
        detail: String?,
        relatedId: String?,
    ) = Unit

    override suspend fun logException(
        type: AppLogType,
        tag: String,
        message: String,
        throwable: Throwable,
        relatedId: String?,
    ) {
        exceptions += RecordedException(type, tag, message, throwable, relatedId)
    }

    override suspend fun clear() = Unit

    override suspend fun deleteByIds(ids: List<Long>) = Unit

    override suspend fun trim() = Unit

    override suspend fun applyRetentionPolicy() = Unit

    data class RecordedException(
        val type: AppLogType,
        val tag: String,
        val message: String,
        val throwable: Throwable,
        val relatedId: String?,
    )
}
