package com.lonx.lyrico.domain

import com.lonx.lyrico.data.model.lyrics.LyricsResult
import com.lonx.lyrico.data.model.lyrics.SearchSource
import com.lonx.lyrico.data.model.lyrics.SongSearchResult
import com.lonx.lyrico.data.model.lyrics.SourceRuntimeConfig
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The bridge that feeds a user's saved per-source settings into the live sources.
 *
 * Sources are created before the user's config is known and are cached for the life of the app, so
 * this applier is the only thing that ever calls `applyConfig`. These tests drive it against a
 * **real** file-backed settings store and a real source flow — a stubbed settings map would hide
 * exactly the bug this class can have (a source that keeps the config it was constructed with, or a
 * config keyed by a different id than the source's own).
 */
class SearchSourceConfigApplierTest {

    private val workingDir: Path = Files.createTempDirectory("lyrico-source-config-test")
    private val settingsFile: Path = workingDir.resolve("settings.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        workingDir.toFile().deleteRecursively()
    }

    private fun openRepository(): SettingsRepositoryImpl {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return SettingsRepositoryImpl(createSettingsDataStore(settingsFile, scope))
    }

    private fun scope(): CoroutineScope {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scopes += scope
        return scope
    }

    @Test
    fun `a source receives the settings the user saved for its id`() = runBlocking<Unit> {
        val settings = openRepository()
        settings.saveSourceSettings(
            "net.example.a",
            mapOf("baseUrl" to "https://api.example.test", "region" to "cn"),
        )
        val source = RecordingSearchSource("net.example.a")
        val sources = MutableStateFlow<List<SearchSource>>(listOf(source))

        val job = SearchSourceConfigApplier(settings).observeIn(scope(), sources)
        val applied = source.awaitApplied()

        assertEquals(
            mapOf("baseUrl" to "https://api.example.test", "region" to "cn"),
            applied.values,
        )
        job.cancel()
    }

    @Test
    fun `a source with nothing saved receives an empty config, not a stale one`() = runBlocking<Unit> {
        val settings = openRepository()
        settings.saveSourceSettings("net.example.other", mapOf("region" to "jp"))
        val source = RecordingSearchSource("net.example.a")
        val sources = MutableStateFlow<List<SearchSource>>(listOf(source))

        val job = SearchSourceConfigApplier(settings).observeIn(scope(), sources)
        val applied = source.awaitApplied()

        assertEquals(emptyMap(), applied.values)
        job.cancel()
    }

    @Test
    fun `each source in the list gets its own config`() = runBlocking<Unit> {
        val settings = openRepository()
        settings.saveSourceSettings("net.example.a", mapOf("region" to "cn"))
        settings.saveSourceSettings("net.example.b", mapOf("region" to "jp"))
        val a = RecordingSearchSource("net.example.a")
        val b = RecordingSearchSource("net.example.b")
        val c = RecordingSearchSource("net.example.c")
        val sources = MutableStateFlow<List<SearchSource>>(listOf(a, b, c))

        val job = SearchSourceConfigApplier(settings).observeIn(scope(), sources)
        a.awaitApplied()
        b.awaitApplied()
        c.awaitApplied()

        assertEquals(mapOf("region" to "cn"), a.applied.single().values)
        assertEquals(mapOf("region" to "jp"), b.applied.single().values)
        assertEquals(emptyMap(), c.applied.single().values)
        job.cancel()
    }

    @Test
    fun `a source added later is configured as soon as the list emits`() = runBlocking<Unit> {
        val settings = openRepository()
        settings.saveSourceSettings("net.example.late", mapOf("token" to "abc"))
        val first = RecordingSearchSource("net.example.first")
        val sources = MutableStateFlow<List<SearchSource>>(listOf(first))

        val job = SearchSourceConfigApplier(settings).observeIn(scope(), sources)
        first.awaitApplied()

        val late = RecordingSearchSource("net.example.late")
        sources.value = listOf(first, late)

        assertEquals(mapOf("token" to "abc"), late.awaitApplied().values)
        assertEquals(1, late.applied.size, "the new source is configured once per emission")
        job.cancel()
    }

    @Test
    fun `a changed setting reaches the source that is already in the list`() = runBlocking<Unit> {
        val settings = openRepository()
        settings.saveSourceSettings("net.example.a", mapOf("region" to "cn"))
        val source = RecordingSearchSource("net.example.a")
        val sources = MutableStateFlow<List<SearchSource>>(listOf(source))

        val job = SearchSourceConfigApplier(settings).observeIn(scope(), sources)
        assertEquals(mapOf("region" to "cn"), source.awaitApplied().values)

        // The user edits the config in settings. The same cached source instance must pick it up —
        // on desktop the source outlives the settings screen, so a one-shot apply would strand it.
        settings.saveSourceSettings("net.example.a", mapOf("region" to "jp", "quality" to "high"))
        withTimeout(10_000) {
            while (source.applied.size < 2) yield()
        }

        assertEquals(
            mapOf("region" to "jp", "quality" to "high"),
            source.applied.last().values,
            "the latest save wins",
        )
        assertSame(source, source.appliedSources.last(), "the applier reuses the given instances")
        job.cancel()
    }

    @Test
    fun `cancelling the returned job stops applying configs`() = runBlocking<Unit> {
        val settings = openRepository()
        val source = RecordingSearchSource("net.example.a")
        val sources = MutableStateFlow<List<SearchSource>>(listOf(source))

        val job: Job = SearchSourceConfigApplier(settings).observeIn(scope(), sources)
        source.awaitApplied()
        val appliesWhenRunning = source.applied.size

        job.cancelAndJoin()
        val late = RecordingSearchSource("net.example.late")
        sources.value = listOf(source, late)

        assertEquals(0, late.applied.size, "a cancelled applier cannot configure a new source")
        assertEquals(appliesWhenRunning, source.applied.size)
    }

    /**
     * A `SearchSource` that only records what was applied to it.
     *
     * The search entry points are not part of this behaviour, so they answer as an empty source
     * would; a call reaching them would be a bug in the applier's wiring.
     */
    private class RecordingSearchSource(override val id: String) : SearchSource {
        override val name: String = id

        val applied = mutableListOf<SourceRuntimeConfig>()
        val appliedSources = mutableListOf<SearchSource>()
        private val firstApplication = CompletableDeferred<SourceRuntimeConfig>()

        override fun applyConfig(config: SourceRuntimeConfig) {
            applied += config
            appliedSources += this
            firstApplication.complete(config)
        }

        suspend fun awaitApplied(): SourceRuntimeConfig = withTimeout(10_000) { firstApplication.await() }

        override suspend fun searchSongs(
            keyword: String,
            page: Int,
            separator: String,
            pageSize: Int,
        ): List<SongSearchResult> = emptyList()

        override suspend fun getLyrics(song: SongSearchResult): LyricsResult? = null

        override suspend fun searchCovers(
            keyword: String,
            page: Int,
            pageSize: Int,
        ): List<SongSearchResult> = emptyList()
    }
}
