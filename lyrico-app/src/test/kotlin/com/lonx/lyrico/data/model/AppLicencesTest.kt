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
}
