package com.lonx.lyrico.utils

import java.util.Locale

/**
 * File sizes the way Android's `android.text.format.Formatter.formatFileSize` printed them.
 *
 * The Android call site was `Formatter.formatFileSize(context, song.fileSize)`, which has no desktop
 * equivalent — the whole `android.text.format` package is Android-only — so the behaviour it
 * documented is reproduced here instead of being replaced by a differently-formatted size string:
 *
 * - **SI units, 1000-based.** `FLAG_SI` is `formatFileSize`'s default, so 1 kB is 1000 bytes and the
 *   prefixes are `kB`, `MB`, `GB`, `TB`, `PB`. `Formatter.formatShortFileSize`'s IEC variant (1024,
 *   `KiB`) is a different function and was not what the song detail sheet called.
 * - **The step-up threshold is 900, not 1000**, exactly as in AOSP's `formatBytes`: 950 bytes reads
 *   `0.95 kB`, not `950 B`. It looks like an off-by-one and it is AOSP's actual behaviour, so the
 *   test pins it rather than "fixing" it.
 * - **The rounding is two decimal places below 100 and none from 100 up** — *including* the 10..100
 *   band, which reads `95.00 kB` rather than `95.0 kB`. Every branch of AOSP's `roundBytes` was
 *   checked against the source (`FLAG_SI_UNITS` is what the one-argument `formatFileSize` passes, so
 *   the divisor is 1000), and AOSP's own unit test expectation `12_582_912 -> "12.58"` is one of the
 *   cases pinned below.
 *
 * Two things are approximations rather than ports, and they are stated instead of glossed over:
 *
 * - **The unit strings.** AOSP lets ICU's `MeasureFormat` render `MeasureUnit.KILOBYTE`; the SI short
 *   forms (`kB`, `MB`, `GB`, `TB`, `PB`) are reproduced literally here.
 * - **Values under 900 bytes print as `N B`.** AOSP's ICU path may spell the byte unit out (`N byte`)
 *   depending on the CLDR data in the platform. No song file is that small, so the difference is not
 *   reachable from the song detail sheet this exists for; it is written down rather than left to be
 *   discovered.
 *
 * The locale is a parameter rather than an implicit `Locale.getDefault()` so the tests can pin the
 * decimal separator: `Formatter.formatFileSize` used the default locale (a German device printed
 * `1,50 kB`), and a test that asserted on that would pass or fail depending on the machine it ran on.
 * Callers that want "whatever this user's locale does" pass nothing.
 */
object FileSizeFormatter {

    /** 1000-based prefixes, in the order `formatBytes` climbs through them. */
    private val SI_UNITS = listOf("kB", "MB", "GB", "TB", "PB")

    /** AOSP's step-up threshold. Not 1000 — see the class comment. */
    private const val UNIT_THRESHOLD = 900

    fun format(sizeBytes: Long, locale: Locale = Locale.getDefault()): String {
        val isNegative = sizeBytes < 0
        var result = (if (isNegative) -sizeBytes else sizeBytes).toDouble()

        var unitIndex = -1
        while (result > UNIT_THRESHOLD && unitIndex < SI_UNITS.lastIndex) {
            result /= 1000.0
            unitIndex++
        }

        val inBytes = unitIndex < 0
        val unit = if (inBytes) "B" else SI_UNITS[unitIndex]
        // `mult == 1 || result >= 100` in AOSP, i.e. the byte branch and everything from 100 up print
        // no decimals; the rest print two.
        val pattern = if (inBytes || result >= 100) "%.0f" else "%.2f"
        val value = String.format(locale, pattern, result)
        return if (isNegative) "-$value $unit" else "$value $unit"
    }
}
