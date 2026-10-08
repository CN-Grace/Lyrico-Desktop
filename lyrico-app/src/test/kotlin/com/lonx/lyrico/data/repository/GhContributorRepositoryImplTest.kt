package com.lonx.lyrico.data.repository

import com.lonx.lyrico.data.support.LocalGitHubServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GhContributorRepositoryImplTest {

    private lateinit var server: LocalGitHubServer

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val contributorsPath = "/repos/CN-Grace/Lyrico-Desktop/contributors"

    @BeforeTest
    fun setUp() {
        server = LocalGitHubServer().start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    private fun repository(client: okhttp3.OkHttpClient = server.client()) =
        GhContributorRepositoryImpl(json = json, okHttpClient = client)

    private fun contributor(
        id: Int,
        login: String,
        contributions: Int,
        type: String = "User"
    ) = """{"id":$id,"login":"$login","avatar_url":"https://avatars.test/$login.png",""" +
        """"html_url":"https://github.test/$login","contributions":$contributions,"type":"$type"}"""

    @Test
    fun `bots are left out and the rest is ordered by contribution count`() = runBlocking<Unit> {
        server.respond(
            contributorsPath,
            "[${contributor(1, "quiet", 2)}, ${contributor(2, "dependabot[bot]", 99, type = "Bot")}, " +
                "${contributor(3, "loud", 40)}]",
        )

        val contributors = repository().getContributors(OWNER, REPO).getOrThrow()

        assertEquals(listOf("loud", "quiet"), contributors.map { it.login })
        assertEquals(listOf(3, 1), contributors.map { it.id })
        assertTrue(contributors.none { it.type == "Bot" })
    }

    @Test
    fun `the request asks github for the contributors and says who is asking`() = runBlocking<Unit> {
        server.respond(contributorsPath, "[]")

        repository().getContributors(OWNER, REPO).getOrThrow()

        // The query string included, since GitHub pages this endpoint.
        assertEquals(listOf("$contributorsPath?per_page=600"), server.requestedPaths)
    }

    @Test
    fun `a contributor keeps every field the about screen shows`() = runBlocking<Unit> {
        server.respond(contributorsPath, "[${contributor(7, "grace", 5)}]")

        val contributor = repository().getContributors(OWNER, REPO).getOrThrow().single()

        assertEquals(7, contributor.id)
        assertEquals("grace", contributor.login)
        assertEquals("https://avatars.test/grace.png", contributor.avatar_url)
        assertEquals("https://github.test/grace", contributor.html_url)
        assertEquals(5, contributor.contributions)
    }

    @Test
    fun `an empty contributor list is a success, not a failure`() = runBlocking<Unit> {
        server.respond(contributorsPath, "[]")

        assertTrue(repository().getContributors(OWNER, REPO).getOrThrow().isEmpty())
    }

    @Test
    fun `a repository github does not know is reported as a failure`() = runBlocking<Unit> {
        val result = repository().getContributors(OWNER, "no-such-repo")

        val error = assertNotNull(result.exceptionOrNull())
        assertTrue(error.message!!.contains("404"), "expected the status in ${error.message}")
    }

    @Test
    fun `a body that is not the expected json is reported as a failure`() = runBlocking<Unit> {
        server.respond(contributorsPath, "not json at all")

        val result = repository().getContributors(OWNER, REPO)

        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `a server that is not there is reported as a network failure`() = runBlocking<Unit> {
        val dead = LocalGitHubServer().start()
        val client = dead.client()
        dead.stop()

        val error = assertNotNull(repository(client).getContributors(OWNER, REPO).exceptionOrNull())

        assertEquals("网络错误", error.message)
    }

    private companion object {
        const val OWNER = "CN-Grace"
        const val REPO = "Lyrico-Desktop"
    }
}
