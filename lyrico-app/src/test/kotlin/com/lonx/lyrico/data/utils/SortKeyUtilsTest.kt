package com.lonx.lyrico.data.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the group/sort keys the library list is ordered by.
 *
 * These keys are *stored* in `songs.titleGroupKey` / `titleSortKey` (and the artist/album pairs), so
 * this test is also the record of a dependency substitution: Android computed them with tinypinyin,
 * which only ever shipped as an Android AAR on jcenter and cannot be resolved from Maven Central.
 * The desktop build uses `com.github.houbb:pinyin` instead, and the CJK cases below are what that
 * library produces — a change in either the library or the calling convention has to be a deliberate
 * edit here, not a silent one.
 */
class SortKeyUtilsTest {

    @Test
    fun `numeric titles group under zero`() {
        val keys = SortKeyUtils.getSortKeys("1989")
        assertEquals("0", keys.groupKey)
        assertEquals("0_1989", keys.sortKey)
    }

    @Test
    fun `ascii titles group by their uppercased first letter`() {
        val keys = SortKeyUtils.getSortKeys("hotel california")
        assertEquals("H", keys.groupKey)
        assertEquals("1_HOTEL CALIFORNIA", keys.sortKey)
    }

    @Test
    fun `chinese titles group by full pinyin`() {
        val keys = SortKeyUtils.getSortKeys("中文歌")
        assertEquals("Z", keys.groupKey)
        assertEquals("1_ZHONGWENGE", keys.sortKey)
    }

    @Test
    fun `chinese phrases use the phrase reading for the first letter`() {
        // 重 is polyphonic; only the phrase dictionary knows 重庆 is "chongqing", not "zhongqing".
        val keys = SortKeyUtils.getSortKeys("重庆森林")
        assertEquals("C", keys.groupKey)
        assertEquals("1_CHONGQINGSENLIN", keys.sortKey)
    }

    @Test
    fun `leading punctuation before a chinese title is kept in the sort key`() {
        val keys = SortKeyUtils.getSortKeys("《中文》")
        assertEquals("#", keys.groupKey)
        assertEquals("2_《中文》", keys.sortKey)
    }

    @Test
    fun `blank input falls back to the catch-all group`() {
        assertEquals(SortKeyUtils.SortKeys("#", "2_"), SortKeyUtils.getSortKeys("   "))
        assertEquals(SortKeyUtils.SortKeys("#", "2_"), SortKeyUtils.getSortKeys(""))
    }

    @Test
    fun `text without pinyin falls back to the raw title under the catch-all group`() {
        // Kana has no Chinese pinyin reading, so the library returns the text unchanged and the
        // first "character" is not a latin letter.
        val keys = SortKeyUtils.getSortKeys("こんにちは")
        assertEquals("#", keys.groupKey)
        assertEquals("2_こんにちは", keys.sortKey)
    }
}
