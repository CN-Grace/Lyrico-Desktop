package com.lonx.lyrico.utils

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Android `Formatter.formatFileSize` behaviour that the song detail sheet used to print.
 *
 * Every expected value below was taken from AOSP's `Formatter.roundBytes` (the one-argument
 * `formatFileSize` passes `FLAG_SI_UNITS`, so the divisor is 1000 and the threshold is 900), and the
 * `12_582_912` case is AOSP's own unit-test expectation. Two details in there are surprising enough
 * that the tests exist mainly to stop a later "cleanup" from changing them:
 *
 * - 950 bytes is `0.95 kB`, not `950 B` — the climb uses `> 900`.
 * - 95_000 bytes is `95.00 kB`, not `95.0 kB` — the 10..100 band keeps two decimals because only
 *   `FLAG_SHORTER` (which `formatShortFileSize` passes, and the detail sheet did not) drops them.
 *
 * The locale is pinned so the result does not depend on the machine running the tests.
 */
class FileSizeFormatterTest {

    private val us = Locale.US

    @Test
    fun `sizes below the step-up threshold stay in bytes with no decimals`() {
        assertEquals("0 B", FileSizeFormatter.format(0, us))
        assertEquals("1 B", FileSizeFormatter.format(1, us))
        assertEquals("900 B", FileSizeFormatter.format(900, us))
    }

    @Test
    fun `one byte past the threshold climbs to kilobytes`() {
        assertEquals(
            "0.95 kB",
            FileSizeFormatter.format(950, us),
            "AOSP climbs at > 900, so 950 bytes is 0.95 kB rather than 950 B",
        )
    }

    @Test
    fun `kilobyte values keep two decimals below a hundred`() {
        assertEquals("1.00 kB", FileSizeFormatter.format(1_000, us))
        assertEquals("1.50 kB", FileSizeFormatter.format(1_500, us))
        assertEquals("9.50 kB", FileSizeFormatter.format(9_500, us))
        assertEquals(
            "95.00 kB",
            FileSizeFormatter.format(95_000, us),
            "only FLAG_SHORTER drops the decimals in the 10..100 band, and the detail sheet did not pass it",
        )
    }

    @Test
    fun `a value above nine hundred kilobytes climbs again instead of printing three digits`() {
        // The climb is a `while (result > 900)`, not `while (result >= 1000)`, so it does not stop at
        // "950 kB" — 900 kB is the largest kilobyte value that exists, and 1 kB past it is 0.90 MB.
        assertEquals("900 kB", FileSizeFormatter.format(900_000, us))
        assertEquals("0.90 MB", FileSizeFormatter.format(901_000, us))
        assertEquals("0.95 MB", FileSizeFormatter.format(950_000, us))
        assertEquals(
            "1.00 MB",
            FileSizeFormatter.format(999_000, us),
            "0.999 MB rounds up to 1.00 MB rather than being reported as 999 kB",
        )
    }

    @Test
    fun `values above a megabyte keep climbing the SI prefixes`() {
        assertEquals("1.00 MB", FileSizeFormatter.format(1_000_000, us))
        assertEquals("1.50 MB", FileSizeFormatter.format(1_500_000, us))
        assertEquals(
            "12.58 MB",
            FileSizeFormatter.format(12_582_912, us),
            "AOSP's own unit test expects exactly this for 12_582_912 with FLAG_SI_UNITS",
        )
        assertEquals("1.00 GB", FileSizeFormatter.format(1_000_000_000, us))
        assertEquals("1.00 TB", FileSizeFormatter.format(1_000_000_000_000, us))
        assertEquals("1.00 PB", FileSizeFormatter.format(1_000_000_000_000_000, us))
    }

    @Test
    fun `units stop at petabytes instead of inventing prefixes`() {
        // 1e18 is past PB; AOSP's climb is a fixed chain of `if`s, so it stops at the last one.
        assertEquals("1000 PB", FileSizeFormatter.format(1_000_000_000_000_000_000, us))
    }

    @Test
    fun `the decimal separator follows the requested locale`() {
        assertEquals(
            "1,50 kB",
            FileSizeFormatter.format(1_500, Locale.GERMANY),
            "the formatter must not bake in the US separator",
        )
    }

    @Test
    fun `a negative size keeps AOSP's leading minus sign`() {
        assertEquals("-1.50 kB", FileSizeFormatter.format(-1_500, us))
    }
}
