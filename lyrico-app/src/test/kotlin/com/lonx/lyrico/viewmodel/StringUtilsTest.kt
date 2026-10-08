package com.lonx.lyrico.viewmodel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `isEqualIgnoringBlank` treats null and blank as the same value, which is what makes it useful for
 * "did the user actually change this tag field" checks: a field that was absent and a field the user
 * cleared are the same edit.
 *
 * The part worth pinning is what it does *not* do — it compares blankness, not trimmed content, so
 * `" a"` and `"a"` are different and whitespace is never normalised away.
 */
class StringUtilsTest {

    @Test
    fun `two nulls are equal`() {
        assertTrue(null.isEqualIgnoringBlank(null))
    }

    @Test
    fun `null and empty are equal`() {
        assertTrue(null.isEqualIgnoringBlank(""))
        assertTrue("".isEqualIgnoringBlank(null))
    }

    @Test
    fun `null and whitespace-only are equal`() {
        assertTrue(null.isEqualIgnoringBlank("   "))
        assertTrue("\t\n".isEqualIgnoringBlank(null))
    }

    @Test
    fun `empty and whitespace-only are equal`() {
        assertTrue("".isEqualIgnoringBlank(" "))
    }

    @Test
    fun `identical text is equal`() {
        assertTrue("bladeenc".isEqualIgnoringBlank("bladeenc"))
    }

    @Test
    fun `text differing only in case is not equal`() {
        // Tag comparison is exact; case folding is a separate concern.
        assertFalse("Bladeenc".isEqualIgnoringBlank("bladeenc"))
    }

    @Test
    fun `different text is not equal`() {
        assertFalse("bladeenc".isEqualIgnoringBlank("l3enc"))
    }

    @Test
    fun `text against null or blank is not equal`() {
        assertFalse("bladeenc".isEqualIgnoringBlank(null))
        assertFalse(null.isEqualIgnoringBlank("bladeenc"))
        assertFalse("bladeenc".isEqualIgnoringBlank(""))
    }

    @Test
    fun `leading and trailing whitespace is compared, not trimmed`() {
        // The function asks "is either side blank", not "are the trimmed strings equal".
        assertFalse(" bladeenc".isEqualIgnoringBlank("bladeenc"))
        assertFalse("bladeenc".isEqualIgnoringBlank("bladeenc "))
        assertFalse(" bladeenc".isEqualIgnoringBlank("bladeenc "))
    }

    @Test
    fun `identical text with the same surrounding whitespace is equal`() {
        assertTrue(" bladeenc ".isEqualIgnoringBlank(" bladeenc "))
    }

    @Test
    fun `empty is not equal to a non-blank value on either side`() {
        // The asymmetry that a naive `this == other` would get wrong in the other direction.
        assertFalse("".isEqualIgnoringBlank("bladeenc"))
        assertFalse("bladeenc".isEqualIgnoringBlank(""))
    }
}
