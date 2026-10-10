package com.lonx.lyrico.plugin.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The language preferences a plugin's string catalogs are resolved against.
 *
 * Desktop replacement for the Android original. Android read the running `Configuration` (a full
 * ordered priority list, plus AppCompat's per-app language override, refreshed by a
 * `ComponentCallbacks` registration); the JVM has neither. The desktop port therefore derives a
 * single-entry preference list from [Locale.getDefault] — which is also the locale Compose
 * Multiplatform resolves the app's own strings from, so a plugin and the surrounding UI agree. The
 * real-window evidence backs that up: the shell renders Chinese because the system locale is
 * `zh-CN`.
 *
 * Consequences, recorded rather than papered over:
 *
 * - **One preference, not a list.** A user who lists several languages in Windows does not hand the
 *   JVM a priority list; the fallback chain below the first entry is the plugin's own `defaultLocale`
 *   (see [PluginStrings.snapshot]).
 * - **No runtime language switch.** Android re-resolved on a configuration change; here the value is
 *   read once at class initialisation ([initialize] only re-reads it). A changed Windows display
 *   language takes effect on the next launch, which is when the JVM picks the new default locale.
 * - **No ICU likely-subtags.** Android injected `ULocale.addLikelySubtags` into
 *   [PluginStrings.inferScript] and used it to match `zh-Hans`/`zh-Hant` catalogs. The desktop port
 *   keeps [PluginStrings]' built-in zh fallback and does *not* invent tables for other languages, so
 *   a catalog split by script for e.g. `sr`/`az`/`mn` resolves through language-only matching.
 */
object PluginLocales {

    private val mutablePreferences = MutableStateFlow(systemPreferences())

    /** BCP 47 language tags, most preferred first. Never empty: the system locale is the last resort. */
    val preferences: StateFlow<List<String>> = mutablePreferences.asStateFlow()

    /**
     * Re-reads the system locale. Kept so the DI startup path can mirror Android's
     * `PluginLocales.initialize(context)` call site; safe to call more than once.
     */
    fun initialize() {
        mutablePreferences.value = systemPreferences()
    }

    /** Overrides the preference list, most preferred first. Used by tests and by explicit selection. */
    fun update(languageTags: List<String>) {
        mutablePreferences.value = languageTags.filter { it.isNotBlank() }.ifEmpty { systemPreferences() }
    }

    /** Overrides the preference list with a single locale. */
    fun update(locale: Locale) {
        update(listOf(locale.toLanguageTag()))
    }

    private fun systemPreferences(): List<String> {
        val locale = Locale.getDefault()
        val tag = locale.toLanguageTag()
        // A language tag without a locale is "und", which PluginStrings would treat as a real
        // preference; fall back to the bare language in that case.
        return if (tag.isBlank() || tag == "und") listOf(Locale.ENGLISH.toLanguageTag()) else listOf(tag)
    }
}
