package com.lonx.lyrico.plugin.i18n

import com.lonx.lyrico.data.model.plugin.PluginI18n
import com.lonx.lyrico.data.model.plugin.PluginManifest
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop language-preference source for plugin string catalogs.
 *
 * Android's original read the running `Configuration`; the JVM has no such thing, so the port derives
 * a one-entry list from [Locale.getDefault]. Two things are worth pinning here: that the JVM default
 * locale really is what a plugin sees, and that the list can never be empty — [PluginStrings] would
 * silently fall back to `defaultLocale` for an empty preference list, which would look like a bug in
 * a plugin's catalog rather than in the locale plumbing.
 *
 * The JVM's default locale is process-global, so the test that changes it puts it back.
 */
class PluginLocalesTest {

    private lateinit var originalLocale: Locale

    @BeforeTest
    fun setUp() {
        originalLocale = Locale.getDefault()
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(originalLocale)
        PluginLocales.initialize()
    }

    @Test
    fun `the preference list is the JVM default locale`() {
        Locale.setDefault(Locale.forLanguageTag("ja-JP"))

        PluginLocales.initialize()

        assertEquals(listOf("ja-JP"), PluginLocales.preferences.value)
    }

    @Test
    fun `a system locale without a language falls back to English instead of und`() {
        // `Locale.ROOT.toLanguageTag()` is "und" — an empty preference, not a real language.
        Locale.setDefault(Locale.ROOT)

        PluginLocales.initialize()

        assertEquals(listOf("en"), PluginLocales.preferences.value)
    }

    @Test
    fun `an explicit locale replaces the system preference`() {
        PluginLocales.update(Locale.forLanguageTag("zh-Hant-TW"))

        assertEquals(listOf("zh-Hant-TW"), PluginLocales.preferences.value)
    }

    @Test
    fun `blank entries are dropped and an all blank list falls back to the system locale`() {
        Locale.setDefault(Locale.forLanguageTag("ko-KR"))
        PluginLocales.initialize()

        PluginLocales.update(listOf("  ", "fr", ""))
        assertEquals(listOf("fr"), PluginLocales.preferences.value)

        PluginLocales.update(listOf("   "))
        assertEquals(listOf("ko-KR"), PluginLocales.preferences.value)
    }

    @Test
    fun `the flow carries every change to its collectors`() {
        val seen = mutableListOf<List<String>>()
        seen += PluginLocales.preferences.value

        PluginLocales.update(Locale.forLanguageTag("de-DE"))
        seen += PluginLocales.preferences.value

        assertTrue(seen.last() == listOf("de-DE"), seen.toString())
        assertTrue(PluginLocales.preferences.value.isNotEmpty())
    }

    @Test
    fun `the preference the DI graph publishes is what a plugin's catalog resolves against`() {
        val root = Files.createTempDirectory("lyrico-plugin-locales").toFile()
        try {
            File(root, "en.json").writeText("""{"title":"English"}""")
            File(root, "zh.json").writeText("""{"title":"中文"}""")
            val manifest = PluginManifest(
                id = "net.example.locales",
                name = "Locales",
                versionCode = 1,
                versionName = "1.0.0",
                apiVersion = 5,
                minHostApiVersion = 4,
                i18n = PluginI18n(
                    defaultLocale = "en",
                    resources = mapOf("en" to "en.json", "zh-Hans" to "zh.json"),
                ),
            )
            val strings = PluginStrings.load(root, manifest)

            PluginLocales.update(Locale.forLanguageTag("zh-CN"))
            val chinese = strings.snapshot(PluginLocales.preferences.value)
            assertEquals("zh-Hans", chinese.locale)
            assertEquals("中文", chinese.text("@title"))

            PluginLocales.update(Locale.forLanguageTag("en-GB"))
            val english = strings.snapshot(PluginLocales.preferences.value)
            assertEquals("en", english.locale)
            assertEquals("English", english.text("@title"))
        } finally {
            root.deleteRecursively()
        }
    }
}
