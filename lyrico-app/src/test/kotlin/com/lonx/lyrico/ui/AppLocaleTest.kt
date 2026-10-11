package com.lonx.lyrico.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.data.model.AppLanguage
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.cache_category_network
import com.lonx.lyrico.resources.clear_cache
import com.lonx.lyrico.resources.song_list_title
import com.lonx.lyrico.utils.formattedString
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The language seam: an explicit app-language setting has to decide what `stringResource` returns, and it
 * has to do it for the whole UI and for messages resolved outside a composition.
 *
 * The assertions are on strings that differ in all four catalogs this project ships, so a test cannot pass
 * by accident:
 *
 * - `clear_cache` is "Clear Cache" (en), "清理缓存" (zh-CN), "清除快取" (zh-TW and zh-HK);
 * - `cache_category_network` is "網路快取" (zh-TW) but "網絡快取" (zh-HK) — the only pair that separates
 *   the two traditional variants, and therefore the only way to prove the *region* qualifier is honoured
 *   rather than just the language.
 *
 * The live-switch test is the one that matters most. Compose Resources caches a resolved string per
 * resource *and environment*, so a seam that only set the language at startup would leave everything
 * already on screen in the old language; that test recomposes after the switch and asserts the rendered
 * value changed.
 */
@OptIn(ExperimentalTestApi::class)
class AppLocaleTest {

    @AfterTest
    fun tearDown() {
        // `AppLocaleState` is process-wide by design (see AppLocale.kt): a test that left a language
        // behind would decide the language of any later test that resolves a string outside a
        // composition -- including FormattedStringResourceTest, in this same JVM.
        AppLocaleState.current.value = AppLanguage.SYSTEM
    }

    /** Composes [resource] under [language] and returns what was rendered. */
    private fun render(language: AppLanguage, resource: StringResource): String {
        var rendered: String? = null
        runComposeUiTest {
            setContent { ProvideAppLocale(language) { rendered = stringResource(resource) } }
        }
        return rendered ?: error("the composition rendered nothing")
    }

    /** Composes [resource] with no provider, to compare against [AppLanguage.SYSTEM]. */
    private fun renderUnprovided(resource: StringResource): String {
        var rendered: String? = null
        runComposeUiTest {
            setContent { rendered = stringResource(resource) }
        }
        return rendered ?: error("the composition rendered nothing")
    }

    private fun renderWith(language: AppLanguage): String =
        render(language, Res.string.clear_cache)

    @Test
    fun `an explicit language decides which catalog is read`() {
        val english = renderWith(AppLanguage.ENGLISH)
        val simplified = renderWith(AppLanguage.SIMPLIFIED_CHINESE)

        assertEquals("Clear Cache", english)
        assertEquals("清理缓存", simplified)
    }

    @Test
    fun `the region qualifier decides between the two traditional variants`() {
        val taiwan = render(AppLanguage.TRADITIONAL_CHINESE_TAIWAN, Res.string.cache_category_network)
        val hongKong = render(AppLanguage.TRADITIONAL_CHINESE_HONG_KONG, Res.string.cache_category_network)

        assertEquals("網路快取", taiwan)
        assertEquals("網絡快取", hongKong)
        assertNotEquals(taiwan, hongKong, "the two variants are the same string, so this proves nothing")
    }

    @Test
    fun `switching the language re-resolves a string that is already composed`() {
        var language by mutableStateOf(AppLanguage.SIMPLIFIED_CHINESE)
        val rendered = mutableListOf<String>()

        runComposeUiTest {
            setContent {
                ProvideAppLocale(language) { rendered += stringResource(Res.string.clear_cache) }
            }
            waitForIdle()
            assertEquals("清理缓存", rendered.last(), "the configured language was not in force at first render")

            runOnIdle { language = AppLanguage.ENGLISH }
            waitForIdle()

            assertEquals(
                "Clear Cache",
                rendered.last(),
                "the switch never reached the already-composed string; the environment-keyed cache still holds the old one",
            )
        }
    }

    @Test
    fun `the region of an explicit language separates zh-CN from zh-TW`() {
        assertEquals("清理缓存", renderWith(AppLanguage.SIMPLIFIED_CHINESE))
        assertEquals("清除快取", renderWith(AppLanguage.TRADITIONAL_CHINESE_TAIWAN))
    }

    @Test
    fun `follow system resolves exactly like no provider at all`() {
        val withProvider = renderWith(AppLanguage.SYSTEM)
        val withoutProvider = renderUnprovided(Res.string.clear_cache)
        val libraryDefault = runBlocking { getString(Res.string.clear_cache) }

        assertEquals(withoutProvider, withProvider, "SYSTEM changed the result, so it is not a pass-through")
        assertEquals(libraryDefault, withProvider, "SYSTEM disagrees with the library's own resolution")
    }

    @Test
    fun `resolution outside a composition follows the configured language`() {
        AppLocaleState.current.value = AppLanguage.SIMPLIFIED_CHINESE
        assertEquals("清理缓存", runBlocking { formattedString(Res.string.clear_cache) })
        assertEquals("歌曲（4）", runBlocking { formattedString(Res.string.song_list_title, 4) })

        AppLocaleState.current.value = AppLanguage.ENGLISH
        assertEquals("Clear Cache", runBlocking { formattedString(Res.string.clear_cache) })
        assertEquals(
            "Songs (4)",
            runBlocking { formattedString(Res.string.song_list_title, 4) },
            "the coroutine path lost its format arguments",
        )
    }

    @Test
    fun `an unrecognised stored tag falls back to follow system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag("xx-YY"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag(AppLanguage.SYSTEM.languageTag))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE_HONG_KONG, AppLanguage.fromLanguageTag("zh-HK"))
    }
}
