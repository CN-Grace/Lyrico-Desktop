package com.lonx.lyrico.data.repository

import com.lonx.lyrico.BuildInfo
import com.lonx.lyrico.data.dto.GitHubReleaseDTO
import com.lonx.lyrico.data.dto.ReleaseInfo
import com.lonx.lyrico.data.model.UpdateCheckResult
import com.lonx.lyrico.utils.logging.PlatformLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

class UpdateRepositoryImpl(
    private val json: Json,
    private val okHttpClient: OkHttpClient
) : UpdateRepository {

    private val TAG = "UpdateRepositoryImpl"

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun checkForUpdate(
        owner: String,
        repo: String
    ): UpdateCheckResult = withContext(Dispatchers.IO) {

        PlatformLog.d(TAG, "开始检查更新")

        val requestUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"

        try {
            val request = Request.Builder()
                .url(requestUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Lyrico-App")
                .build()

            okHttpClient.newCall(request).execute().use { response ->

                if (!response.isSuccessful) {
                    throw ApiResponseException(response.code, response.message)
                }

                val release = json.decodeFromStream<GitHubReleaseDTO>(
                    response.body.byteStream()
                )

                val latestVersionName = release.tag_name
                val releaseNotes = release.body.orEmpty()
                val releaseUrl = release.html_url

                PlatformLog.d(
                    TAG,
                    "最新版本: $latestVersionName 当前版本: ${BuildInfo.VERSION_NAME}"
                )

                val hasUpdate = isNewerVersion(
                    latestVersionName,
                    BuildInfo.VERSION_NAME
                )

                if (hasUpdate) {
                    UpdateCheckResult.NewVersion(
                        ReleaseInfo(
                            versionName = latestVersionName,
                            releaseNotes = releaseNotes,
                            url = releaseUrl
                        )
                    )
                } else {
                    UpdateCheckResult.NoUpdateAvailable
                }
            }

        } catch (e: SocketTimeoutException) {
            PlatformLog.e(TAG, "连接超时", e)
            UpdateCheckResult.TimeoutError

        } catch (e: ApiResponseException) {
            // GitHub 的 4xx/5xx 是「接口拒绝」，不是「网络不通」：Android 版把它扔进 IOException 分支，
            // 于是 UpdateCheckResult.ApiError 这个状态永远不可能出现，UpdateManager 里那句
            // 「接口错误」文案也就成了死代码。桌面端把状态与语义对齐。
            PlatformLog.e(TAG, "更新检查接口错误 HTTP ${e.code}", e)
            UpdateCheckResult.ApiError(code = e.code, message = e.message.orEmpty())

        } catch (e: SerializationException) {
            PlatformLog.e(TAG, "JSON 解析错误", e)
            UpdateCheckResult.ParsingError

        } catch (e: IOException) {
            PlatformLog.e(TAG, "更新检查网络错误", e)
            UpdateCheckResult.NetworkError(e)
        }
    }

    /**
     * A non-2xx answer from the GitHub API. Carries the status so the caller can report it as an API
     * error instead of a network one, and is caught before the general `IOException` branch.
     */
    private class ApiResponseException(
        val code: Int,
        message: String?
    ) : IOException("HTTP $code ${message.orEmpty()}".trim())

    /**
     * 版本比较
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun normalize(version: String): List<Int> {
            return version
                .trim()
                .removePrefix("v")
                .substringBefore("-")
                .substringBefore("+")
                .split(".")
                .map { it.toIntOrNull() ?: 0 }
        }

        val lParts = normalize(latest)
        val cParts = normalize(current)

        PlatformLog.d(TAG, "比较版本: $lParts vs $cParts")

        val max = maxOf(lParts.size, cParts.size)

        for (i in 0 until max) {
            val lv = lParts.getOrElse(i) { 0 }
            val cv = cParts.getOrElse(i) { 0 }

            when {
                lv > cv -> return true
                lv < cv -> return false
            }
        }
        return false
    }
}