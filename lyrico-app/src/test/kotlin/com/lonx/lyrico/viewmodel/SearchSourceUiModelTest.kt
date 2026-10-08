package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.model.lyrics.LyricsResult
import com.lonx.lyrico.data.model.lyrics.SearchSource
import com.lonx.lyrico.data.model.lyrics.SongSearchResult
import com.lonx.lyrico.data.model.plugin.PluginCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The search-source list item and the mapping that builds it.
 *
 * Small, but it is the boundary the plugin list is rendered from, and one field of it was a
 * `@StringRes Int` on Android — so the port question "did the label become a `StringResource` without
 * losing the rest of the mapping" is worth an explicit answer rather than a compile that happens to
 * pass.
 */
class SearchSourceUiModelTest {

    /**
     * The mapping only reads [id]/[name]/[iconPath]/[capabilities]; the four search entry points are
     * abstract on the interface, so they are stubbed. They throw rather than return empty so that a
     * mapping that starts calling one is a failure instead of a silent "no results".
     */
    private class FakeSearchSource(
        override val id: String,
        override val name: String,
        override val iconPath: String? = null,
        override val capabilities: Set<PluginCapability> = setOf(PluginCapability.SEARCH_SONGS),
    ) : SearchSource {
        override suspend fun searchSongs(
            keyword: String,
            page: Int,
            separator: String,
            pageSize: Int,
        ): List<SongSearchResult> = unsupported("searchSongs")

        override suspend fun getLyrics(song: SongSearchResult): LyricsResult? = unsupported("getLyrics")

        override suspend fun searchCovers(
            keyword: String,
            page: Int,
            pageSize: Int,
        ): List<SongSearchResult> = unsupported("searchCovers")

        private fun unsupported(what: String): Nothing =
            throw NotImplementedError("$what is not part of the UI mapping")
    }

    @Test
    fun `identity fields are carried over unchanged`() {
        val source = FakeSearchSource(id = "plugin.lyrics", name = "Lyrics Wiki", iconPath = "H:\\icons\\lw.png")

        val model = source.toUiModel()

        assertEquals("plugin.lyrics", model.id)
        assertEquals("Lyrics Wiki", model.name)
        assertEquals("H:\\icons\\lw.png", model.iconPath)
    }

    @Test
    fun `a null icon stays null rather than becoming a path`() {
        val model = FakeSearchSource(id = "a", name = "A").toUiModel()

        assertNull(model.iconPath)
    }

    @Test
    fun `a source that can fetch lyrics says so`() {
        val source = FakeSearchSource(
            id = "a",
            name = "A",
            capabilities = setOf(PluginCapability.SEARCH_SONGS, PluginCapability.GET_LYRICS),
        )

        assertTrue(source.toUiModel().supportsLyrics)
    }

    @Test
    fun `a source without the lyric capability does not`() {
        val source = FakeSearchSource(
            id = "a",
            name = "A",
            capabilities = setOf(PluginCapability.SEARCH_SONGS, PluginCapability.SEARCH_COVERS),
        )

        assertFalse(source.toUiModel().supportsLyrics)
    }

    @Test
    fun `the capability set alone decides it, not the source's other fields`() {
        // Same id/name/icon, only the capability differs: this is what makes the flag a capability
        // check rather than, say, a name match on "lyrics".
        val withLyrics = FakeSearchSource(
            id = "same",
            name = "same",
            capabilities = setOf(PluginCapability.GET_LYRICS),
        ).toUiModel()
        val without = FakeSearchSource(
            id = "same",
            name = "same",
            capabilities = setOf(PluginCapability.SEARCH_SONGS),
        ).toUiModel()

        assertTrue(withLyrics.supportsLyrics)
        assertFalse(without.supportsLyrics)
    }

    @Test
    fun `the label is left unset by the mapping`() {
        // The field is a `StringResource?` on desktop (it was an `@StringRes Int?`), and the mapping
        // does not set it -- so a non-null value here would mean someone changed the mapping.
        assertNull(FakeSearchSource(id = "a", name = "A").toUiModel().labelRes)
    }
}
