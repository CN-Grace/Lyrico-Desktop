package com.lonx.lyrico.utils

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.app_name
import com.lonx.lyrico.resources.cache_total
import com.lonx.lyrico.resources.plugin_type_with_value
import com.lonx.lyrico.resources.search_field_preview
import com.lonx.lyrico.resources.search_settings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `UiMessage` resolution.
 *
 * Only [UiMessage.resolve] is covered here — [UiMessage.asString] is `@Composable` and cannot be
 * called outside a composition, so the composable path is exercised where it is used rather than
 * here. The two paths differ only in which Compose function they call, so what these tests establish
 * about resources and arguments carries over.
 *
 * **On the assertions about resolved text:** the string files are translated
 * (`plugin_type_with_value` is `Type: %1$s` in `values/` and `插件类型：%1$s` in `values-zh-rCN/`),
 * and Compose resources resolve against the JVM's locale, so asserting an exact expected sentence
 * would pass or fail depending on the machine. Most tests here assert the properties that hold in
 * every locale — the resource loads, the placeholder is consumed, the argument lands, arguments keep
 * their order. The one exact-text assertion uses [Res.string.app_name], which is marked
 * `translatable="false"` and is absent from every `values-zh-*` file, so it is `Lyrico` everywhere.
 */
class UiMessageTest {

    @Test
    fun `already-final text resolves to itself`() = runBlocking<Unit> {
        assertEquals("bladeenc.mp3", UiMessage.DynamicString("bladeenc.mp3").resolve())
    }

    @Test
    fun `already-final text may be absent`() = runBlocking<Unit> {
        assertNull(UiMessage.DynamicString(null).resolve())
    }

    @Test
    fun `an empty string stays an empty string`() = runBlocking<Unit> {
        // Not the same as null: only null means "no message", and flattening the two here would hide
        // a message the UI should still render as (empty) text.
        assertEquals("", UiMessage.DynamicString("").resolve())
    }

    @Test
    fun `a resource resolves to its text without a composition`() = runBlocking<Unit> {
        // Two claims in one. First, the reason `asString(context)` could be replaced by a suspending
        // call: resources are reachable from a plain JVM test with no Android Context and no
        // composition. Second, that a key missing from a locale file (as this one is from every
        // `values-zh-*`) falls back to the default file instead of coming back empty or throwing.
        assertEquals("Lyrico", UiMessage.Localized(Res.string.app_name).resolve())
    }

    @Test
    fun `different keys resolve to different text`() = runBlocking<Unit> {
        // Guards against the resource reference being ignored and one key's text standing in for all.
        // Compared with `!=` rather than an expected value, because this one *is* translated.
        val name = UiMessage.Localized(Res.string.app_name).resolve()
        val settings = UiMessage.Localized(Res.string.search_settings).resolve()

        assertFalse(name.isNullOrBlank())
        assertFalse(settings.isNullOrBlank())
        assertNotEquals(name, settings)
    }

    @Test
    fun `a resource consumes its placeholder and inserts the argument`() = runBlocking<Unit> {
        val text = UiMessage.Localized(Res.string.plugin_type_with_value, "lyrics").resolve().orEmpty()

        assertFalse(text.contains("%1\$s"), "the placeholder must be substituted, got: $text")
        assertTrue(text.contains("lyrics"), "the argument must appear, got: $text")
    }

    @Test
    fun `two arguments land in the order they were given`() = runBlocking<Unit> {
        val text = UiMessage.Localized(Res.string.search_field_preview, "title", "Song").resolve().orEmpty()

        assertFalse(text.contains("%1\$s") || text.contains("%2\$s"), "got: $text")
        val first = text.indexOf("title")
        val second = text.indexOf("Song")
        assertTrue(first >= 0 && second >= 0, "both arguments must appear, got: $text")
        assertTrue(first < second, "arguments must keep their order, got: $text")
    }

    @Test
    fun `a non-string argument is formatted too`() = runBlocking<Unit> {
        val text = UiMessage.Localized(Res.string.cache_total, 42).resolve().orEmpty()

        assertTrue(text.contains("42"), "got: $text")
    }

    @Test
    fun `the arguments are kept on the message`() {
        val message = UiMessage.Localized(Res.string.search_field_preview, "title", "Song")

        assertEquals(listOf("title", "Song"), message.args.toList())
    }

    @Test
    fun `the resource reference is kept on the message`() {
        val message = UiMessage.Localized(Res.string.cache_total, "3")

        assertEquals(Res.string.cache_total, message.res)
    }

    @Test
    fun `two messages naming the same resource are not equal`() {
        // `Localized` is deliberately not a data class, because `vararg` in a data class gives the
        // generated equals array-identity semantics. The consequence is identity equality -- pinned so
        // nobody starts using a message as a state-diffing key.
        val a = UiMessage.Localized(Res.string.cache_total, "3")
        val b = UiMessage.Localized(Res.string.cache_total, "3")

        assertNotEquals<UiMessage>(a, b)
        assertEquals<UiMessage>(a, a)
    }

    @Test
    fun `already-final text still compares by value`() {
        // The half that *is* a data class behaves normally, so a re-emitted identical message is not
        // mistaken for a change.
        assertEquals<UiMessage>(UiMessage.DynamicString("same"), UiMessage.DynamicString("same"))
        assertNotEquals<UiMessage>(UiMessage.DynamicString("a"), UiMessage.DynamicString("b"))
    }
}
