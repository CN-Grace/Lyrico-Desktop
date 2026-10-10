package com.lonx.lyrico.utils

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.search_field_preview
import com.lonx.lyrico.resources.song_list_title
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins why [formattedStringResource] exists.
 *
 * The first test is the tripwire: Compose Multiplatform's own `getString(resource, vararg formatArgs)`
 * only substitutes positional `%n$s` / `%n$d` placeholders, so an Android string such as
 * `歌曲（%d）` comes back with the placeholder still in it. If a future Compose Multiplatform release
 * starts formatting plain placeholders the way Android does, this test fails, and the helper and its
 * guard can be deleted.
 *
 * The other two tests check the helper against the strings this app renders. Their expectations are
 * built with [String.format] rather than written as literals so they do not depend on the machine's
 * locale, and they assert that the placeholder is gone: an expectation built by the broken path would
 * agree with a broken UI.
 */
@OptIn(ExperimentalTestApi::class)
class FormattedStringResourceTest {

    @Test
    fun `the library's own vararg formatting does not substitute plain placeholders`() {
        val raw = runBlocking { getString(Res.string.song_list_title) }
        val throughLibrary = runBlocking { getString(Res.string.song_list_title, 4) }

        assertEquals(raw, throughLibrary, "documented Compose Multiplatform 1.12 behaviour changed")
        assertTrue(
            throughLibrary.contains("%d"),
            "the library substituted the placeholder after all: $throughLibrary"
        )
    }

    @Test
    fun `the helper substitutes a plain placeholder the way String format does`() {
        var rendered: String? = null
        runComposeUiTest {
            setContent { rendered = formattedStringResource(Res.string.song_list_title, 4) }
        }

        val raw = runBlocking { getString(Res.string.song_list_title) }
        val expected = String.format(raw, 4)
        assertEquals(expected, rendered)
        assertFalse(expected == raw, "the expectation itself is unformatted, so the assertion is vacuous")
        assertFalse(rendered!!.contains("%"), "an unsubstituted conversion is still rendered: $rendered")
    }

    @Test
    fun `the helper substitutes every placeholder of a two-argument string`() {
        var rendered: String? = null
        runComposeUiTest {
            setContent {
                rendered = formattedStringResource(Res.string.search_field_preview, "作词", "一个人")
            }
        }

        val raw = runBlocking { getString(Res.string.search_field_preview) }
        assertEquals(String.format(raw, "作词", "一个人"), rendered)
        assertTrue(rendered!!.contains("作词") && rendered!!.contains("一个人"), "an argument is missing: $rendered")
        assertFalse(rendered!!.contains("%"), "an unsubstituted conversion is still rendered: $rendered")
    }
}
