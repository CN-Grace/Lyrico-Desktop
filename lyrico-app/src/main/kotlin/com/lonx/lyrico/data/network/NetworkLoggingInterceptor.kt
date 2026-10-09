package com.lonx.lyrico.data.network

import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.utils.logging.PlatformLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Records failed HTTP calls (non-2xx responses and I/O failures) in the app log.
 *
 * Ported from Android unchanged apart from the logger: `android.util.Log` became [PlatformLog],
 * because the app log row is what the user can actually read afterwards, and the secondary write to
 * logcat has no desktop equivalent.
 */
class NetworkLoggingInterceptor(
    private val appLogRepository: AppLogRepository,
    private val appScope: CoroutineScope
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startNs = System.nanoTime()
        return try {
            val response = chain.proceed(request)
            if (!response.isSuccessful) {
                val durationMs = elapsedMillis(startNs)
                appScope.launch(Dispatchers.IO) {
                    runCatching {
                        appLogRepository.log(
                            level = if (response.code >= 500) AppLogLevel.ERROR else AppLogLevel.WARNING,
                            type = AppLogType.NETWORK,
                            tag = TAG,
                            message = "HTTP ${response.code} ${request.method} ${request.url.host}",
                            detail = buildString {
                                appendLine("url=${request.url}")
                                appendLine("method=${request.method}")
                                appendLine("code=${response.code}")
                                appendLine("protocol=${response.protocol}")
                                appendLine("reasonPhrase=${response.message.ifBlank { "(empty)" }}")
                                appendLine("durationMs=$durationMs")
                            }
                        )
                    }.onFailure {
                        PlatformLog.w(TAG, "Failed to write network response log", it)
                    }
                }
            }
            response
        } catch (e: IOException) {
            val durationMs = elapsedMillis(startNs)
            appScope.launch(Dispatchers.IO) {
                runCatching {
                    appLogRepository.logException(
                        type = AppLogType.NETWORK,
                        tag = TAG,
                        message = "Network request failed: ${request.method} ${request.url.host} (${durationMs}ms)",
                        throwable = e
                    )
                }.onFailure {
                    PlatformLog.w(TAG, "Failed to write network exception log", it)
                }
            }
            throw e
        }
    }

    private fun elapsedMillis(startNs: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    private companion object {
        const val TAG = "NetworkLoggingInterceptor"
    }
}
