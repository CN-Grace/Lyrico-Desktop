package com.lonx.lyrico.data.repository

import com.lonx.lyrico.BuildInfo
import com.lonx.lyrico.data.model.UpdateCheckResult
import com.lonx.lyrico.data.support.LocalGitHubServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The update check against a real HTTP server. The current version is whatever the build stamped into
 * [BuildInfo], so the bodies below are written relative to it: `BuildInfo.VERSION_NAME` is `1.6.0` in
 * this checkout, and the assertions that depend on the exact number are guarded rather than guessed.
 */
class UpdateRepositoryImplTest {

    private lateinit var server: LocalGitHubServer

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val releasePath = "/repos/CN-Grace/Lyrico-Desktop/releases/latest"

    @BeforeTest
    fun setUp() {
        server = LocalGitHubServer().start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    private fun repository(client: okhttp3.OkHttpClient = server.client()) =
        UpdateRepositoryImpl(json = json, okHttpClient = client)

    private fun release(tag: String, notes: String = "notes", url: String = "https://example.test/1") =
        """{"tag_name":"$tag","body":"$notes","html_url":"$url"}"""

    @Test
    fun `a newer release is reported with its notes and its url`() = runBlocking<Unit> {
        assumeTrue("needs BuildInfo.VERSION_NAME to be 1.6.0", BuildInfo.VERSION_NAME == "1.6.0")
        server.respond(releasePath, release("v1.6.1", notes = "- 修复了若干问题", url = "https://example.test/rel"))

        val result = repository().checkForUpdate(OWNER, REPO)

        val newVersion = assertIs<UpdateCheckResult.NewVersion>(result)
        assertEquals("v1.6.1", newVersion.info.versionName)
        assertEquals("- 修复了若干问题", newVersion.info.releaseNotes)
        assertEquals("https://example.test/rel", newVersion.info.url)
        assertEquals(listOf(releasePath), server.requestedPaths)
    }

    @Test
    fun `the same version is not an update`() = runBlocking<Unit> {
        assumeTrue("needs BuildInfo.VERSION_NAME to be 1.6.0", BuildInfo.VERSION_NAME == "1.6.0")
        server.respond(releasePath, release("v1.6.0"))

        assertEquals(UpdateCheckResult.NoUpdateAvailable, repository().checkForUpdate(OWNER, REPO))
    }

    @Test
    fun `an older release is not an update`() = runBlocking<Unit> {
        assumeTrue("needs BuildInfo.VERSION_NAME to be 1.6.0", BuildInfo.VERSION_NAME == "1.6.0")
        server.respond(releasePath, release("v1.5.9"))

        assertEquals(UpdateCheckResult.NoUpdateAvailable, repository().checkForUpdate(OWNER, REPO))
    }

    @Test
    fun `a two digit segment counts as higher, not as a longer string`() = runBlocking<Unit> {
        assumeTrue("needs BuildInfo.VERSION_NAME to be 1.6.0", BuildInfo.VERSION_NAME == "1.6.0")
        // '1' < '6' as text, so a lexicographic comparison would call this "older".
        server.respond(releasePath, release("v1.10.0"))

        assertIs<UpdateCheckResult.NewVersion>(repository().checkForUpdate(OWNER, REPO))
    }

    @Test
    fun `a pre-release suffix is ignored when comparing`() = runBlocking<Unit> {
        assumeTrue("needs BuildInfo.VERSION_NAME to be 1.6.0", BuildInfo.VERSION_NAME == "1.6.0")
        server.respond(releasePath, release("v1.6.0-beta.2"))

        assertEquals(UpdateCheckResult.NoUpdateAvailable, repository().checkForUpdate(OWNER, REPO))
    }

    @Test
    fun `a build metadata suffix is ignored when comparing`() = runBlocking<Unit> {
        assumeTrue("needs BuildInfo.VERSION_NAME to be 1.6.0", BuildInfo.VERSION_NAME == "1.6.0")
        server.respond(releasePath, release("1.6.0+42"))

        assertEquals(UpdateCheckResult.NoUpdateAvailable, repository().checkForUpdate(OWNER, REPO))
    }

    @Test
    fun `a missing release is an api error, not a network error`() = runBlocking<Unit> {
        // The Android implementation threw an IOException for this and reported "网络错误"; the sealed
        // result type has an ApiError state, and UpdateManager renders it as "接口错误".
        val result = repository().checkForUpdate(OWNER, REPO)

        val apiError = assertIs<UpdateCheckResult.ApiError>(result)
        assertEquals(404, apiError.code)
        assertTrue(apiError.message.contains("404"), "the status has to survive into the message")
    }

    @Test
    fun `a 403 rate limit is reported with its status code`() = runBlocking<Unit> {
        server.respond(releasePath, """{"message":"API rate limit exceeded"}""", status = 403)

        val apiError = assertIs<UpdateCheckResult.ApiError>(repository().checkForUpdate(OWNER, REPO))
        assertEquals(403, apiError.code)
    }

    @Test
    fun `a body that is not the expected json is a parsing error`() = runBlocking<Unit> {
        server.respond(releasePath, "not json at all")

        assertEquals(UpdateCheckResult.ParsingError, repository().checkForUpdate(OWNER, REPO))
    }

    @Test
    fun `a json object without a tag name is a parsing error`() = runBlocking<Unit> {
        server.respond(releasePath, """{"body":"no tag here"}""")

        assertEquals(UpdateCheckResult.ParsingError, repository().checkForUpdate(OWNER, REPO))
    }

    @Test
    fun `a server that does not answer in time is a timeout`() = runBlocking<Unit> {
        server.respondSlowly(releasePath, release("v1.6.1"), delayMillis = 2_000)

        val result = repository(client = server.client(readTimeoutMillis = 200)).checkForUpdate(OWNER, REPO)

        assertEquals(UpdateCheckResult.TimeoutError, result)
    }

    @Test
    fun `a server that is not there is a network error`() = runBlocking<Unit> {
        val dead = LocalGitHubServer().start()
        val client = dead.client()
        dead.stop()

        val result = repository(client = client).checkForUpdate(OWNER, REPO)

        assertIs<UpdateCheckResult.NetworkError>(result)
    }

    private companion object {
        const val OWNER = "CN-Grace"
        const val REPO = "Lyrico-Desktop"
    }
}
