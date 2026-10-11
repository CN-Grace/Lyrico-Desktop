package com.lonx.lyrico.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invariants of the hand-maintained licence list.
 *
 * Android generated this list from the resolved dependency graph, so it could not be incomplete *or* wrong.
 * The desktop build has no such plugin and the list is written by hand, which is exactly the kind of data
 * that rots: an entry can lose its licence, two entries can collide on the key the list uses, or a URL can
 * be left as a placeholder. The versions themselves cannot be checked mechanically against
 * `gradle/libs.versions.toml` without parsing it here (they are corrected by hand when that file changes),
 * but everything else can.
 */
class AppLicencesTest {

    @Test
    fun `every entry names itself, a version and at least one licence`() {
        val problems = AppLicences.all.filter { licence ->
            licence.id.isBlank() ||
                licence.name.isBlank() ||
                licence.version.isBlank() ||
                licence.licences.isEmpty() ||
                licence.licences.any { it.isBlank() }
        }

        assertEquals(emptyList(), problems, "an entry is missing part of its identity")
    }

    @Test
    fun `the ids are unique, because the page keys its list on them`() {
        val ids = AppLicences.all.map { it.id }

        assertEquals(ids.size, ids.toSet().size, "two entries share an id: $ids")
    }

    @Test
    fun `every entry links to its upstream project`() {
        val withoutUrl = AppLicences.all.filter { it.url.isNullOrBlank() }

        assertEquals(emptyList(), withoutUrl, "an entry has no URL for the user to follow")
        assertTrue(
            AppLicences.all.all { it.url!!.startsWith("https://") },
            "an entry's URL is not https",
        )
    }

    @Test
    fun `the components the build actually uses are listed`() {
        // A smoke check in the other direction: dropping an entry by accident is as easy as mistyping one, and
        // these are the components the app cannot run without.
        val ids = AppLicences.all.map { it.id }.toSet()
        for (required in listOf("compose-multiplatform", "koin", "room", "okhttp", "coil", "taglib", "ffmpeg")) {
            assertTrue(required in ids, "$required is missing from the licence list")
        }
    }

    @Test
    fun `the ffmpeg entry agrees with the sidecar document`() {
        // `docs/third-party/ffmpeg-lgpl.md` is where the pinned ffmpeg build is recorded (release, hash,
        // licence). The page is hand-written for that entry, so this is the cross-check that keeps the two
        // from drifting: it reads the document and requires the strings it states to appear on the page.
        val doc = java.io.File("../docs/third-party/ffmpeg-lgpl.md")
        org.junit.Assume.assumeTrue("run from the lyrico-app project directory", doc.isFile)
        val text = doc.readText()

        val entry = AppLicences.all.single { it.id == "ffmpeg" }
        assertTrue(
            text.contains(entry.version),
            "the document does not mention the version the page shows: ${entry.version}",
        )
        val haystack = text.lowercase()
        for (licence in entry.licences) {
            // The document writes "LGPL v3" in prose where the page carries the SPDX-shaped `LGPL-3.0`, so the
            // comparison looks for the family and the major version rather than for the literal string.
            val family = licence.substringBefore('-').lowercase()
            val major = licence.substringAfter('-').takeWhile { it.isDigit() }
            assertTrue(
                listOf("$family v$major", "$family-$major", "$family $major").any { it in haystack },
                "the document does not mention the licence the page shows: $licence",
            )
        }
    }
}
