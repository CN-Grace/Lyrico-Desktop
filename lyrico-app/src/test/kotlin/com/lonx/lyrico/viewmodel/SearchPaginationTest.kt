package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.model.lyrics.SongSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Paging merge for plugin search results.
 *
 * Search sources paginate and the same song can come back more than once — pages overlap, a plugin
 * repeats an entry — so the merge has to deduplicate by `(pluginId, id)` while preserving order, and
 * decide whether asking for another page is still worth it. That `hasMore` rule is the subtle part:
 * it is not "did this page add anything", because the first page is allowed to be empty.
 */
class SearchPaginationTest {

    private fun result(pluginId: String, id: String) = SongSearchResult(
        id = id,
        pluginId = pluginId,
        pluginName = pluginId,
        title = "$id-title",
    )

    @Test
    fun `an empty existing list takes the whole page`() {
        val merge = mergeSearchPage(
            existing = emptyList(),
            incoming = listOf(result("p", "1"), result("p", "2")),
            sourceMayHaveMore = true,
        )

        assertEquals(listOf("1", "2"), merge.results.map { it.id })
        assertEquals(2, merge.addedCount)
        assertTrue(merge.hasMore)
    }

    @Test
    fun `results already held are not added twice`() {
        val merge = mergeSearchPage(
            existing = listOf(result("p", "1"), result("p", "2")),
            incoming = listOf(result("p", "2"), result("p", "3")),
            sourceMayHaveMore = true,
        )

        assertEquals(listOf("1", "2", "3"), merge.results.map { it.id })
        assertEquals(1, merge.addedCount)
    }

    @Test
    fun `a repeat inside one page is collapsed`() {
        val merge = mergeSearchPage(
            existing = emptyList(),
            incoming = listOf(result("p", "1"), result("p", "1")),
            sourceMayHaveMore = true,
        )

        assertEquals(1, merge.results.size)
        assertEquals(1, merge.addedCount)
    }

    @Test
    fun `the same id from two plugins is two results`() {
        // Types like "1" are common across plugins, so identity has to include the plugin.
        val merge = mergeSearchPage(
            existing = emptyList(),
            incoming = listOf(result("pluginA", "1"), result("pluginB", "1")),
            sourceMayHaveMore = true,
        )

        assertEquals(2, merge.results.size)
    }

    @Test
    fun `identity cannot be forged by moving characters across the separator`() {
        // The key is "pluginId\u0000id"; plain concatenation would make these two the same result.
        val merge = mergeSearchPage(
            existing = emptyList(),
            incoming = listOf(result("a", "bc"), result("ab", "c")),
            sourceMayHaveMore = true,
        )

        assertEquals(2, merge.results.size)
    }

    @Test
    fun `order is existing first, then new results in page order`() {
        val merge = mergeSearchPage(
            existing = listOf(result("p", "1"), result("p", "2")),
            incoming = listOf(result("p", "3"), result("p", "2"), result("p", "4")),
            sourceMayHaveMore = true,
        )

        assertEquals(listOf("1", "2", "3", "4"), merge.results.map { it.id })
    }

    @Test
    fun `a source with nothing left says so`() {
        val merge = mergeSearchPage(
            existing = listOf(result("p", "1")),
            incoming = emptyList(),
            sourceMayHaveMore = false,
        )

        assertFalse(merge.hasMore)
        assertEquals(0, merge.addedCount)
    }

    @Test
    fun `a source that may have more says so even when the page added nothing`() {
        // The existing implementation keeps paging on the first page regardless, so an empty first
        // page still reports more to come. Pinned as-is: changing it is a behaviour change.
        val merge = mergeSearchPage(
            existing = emptyList(),
            incoming = emptyList(),
            sourceMayHaveMore = true,
        )

        assertTrue(merge.hasMore)
        assertTrue(merge.results.isEmpty())
    }

    @Test
    fun `a page of nothing but repeats ends the walk`() {
        val merge = mergeSearchPage(
            existing = listOf(result("p", "1")),
            incoming = listOf(result("p", "1")),
            sourceMayHaveMore = true,
        )

        assertFalse(merge.hasMore)
        assertEquals(0, merge.addedCount)
    }

    @Test
    fun `a page with new results continues the walk`() {
        val merge = mergeSearchPage(
            existing = listOf(result("p", "1")),
            incoming = listOf(result("p", "1"), result("p", "2")),
            sourceMayHaveMore = true,
        )

        assertTrue(merge.hasMore)
        assertEquals(1, merge.addedCount)
    }

    @Test
    fun `existing results are not copied into the new count`() {
        val merge = mergeSearchPage(
            existing = listOf(result("p", "1"), result("p", "2"), result("p", "3")),
            incoming = listOf(result("p", "4")),
            sourceMayHaveMore = true,
        )

        assertEquals(1, merge.addedCount)
        assertEquals(4, merge.results.size)
    }

    @Test
    fun `the existing list itself is not modified`() {
        val existing = listOf(result("p", "1"))
        val incoming = listOf(result("p", "2"))

        mergeSearchPage(existing, incoming, sourceMayHaveMore = true)

        assertEquals(1, existing.size)
        assertEquals(1, incoming.size)
    }
}
