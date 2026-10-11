@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(InternalResourceApi::class, ExperimentalResourceApi::class)

package com.lonx.lyrico.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.intl.Locale
import com.lonx.lyrico.data.model.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.ComposeEnvironment
import org.jetbrains.compose.resources.DensityQualifier
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.LanguageQualifier
import org.jetbrains.compose.resources.LocalComposeEnvironment
import org.jetbrains.compose.resources.RegionQualifier
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.ScriptQualifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.ThemeQualifier
import org.jetbrains.compose.resources.getSystemEnvironment
import org.jetbrains.compose.resources.getString

/**
 * Makes [language] the language every `stringResource` inside [content] resolves in.
 *
 * Android got this for free: `AppCompatDelegate.setApplicationLocales` re-created the activity with a
 * per-app `Configuration`, and every `getString` on the way down saw the chosen locale. Desktop has no
 * such mechanism, so the equivalent is inserted at the one place the resource system reads the
 * environment from — `LocalComposeEnvironment`.
 *
 * The environment itself is built exactly the way Compose Resources builds its default one (see
 * `DefaultComposeEnvironment` in `ResourceEnvironment.kt`): language/script/region from the locale,
 * theme from [isSystemInDarkTheme], density from [LocalDensity]. **Only the locale differs**; the other
 * two are read from the same composition so an explicit language cannot disturb dark mode or scaling.
 *
 * The switch is live, and that is a property of the library rather than of this file: the composable
 * `stringResource` resolves through `rememberResourceState(resource, …)`, whose remembered value is keyed
 * on the resource *and on the environment*. A new environment therefore re-resolves the string on the next
 * composition — no screen rebuild, and no stale text.
 *
 * Two consequences worth knowing:
 *
 * - The whole `ResourceEnvironment` is rebuilt rather than the locale field being patched, because it is
 *   one immutable value with a five-argument constructor and no copy(). Keeping the other four fields
 *   identical to the default recipe is what makes that safe.
 * - `composeResources` in this project only carries `values`, `values-zh-rCN/rHK/rTW` and `drawable`, so
 *   the theme and density qualifiers have nothing to select between today: they are inert, and a
 *   language switch cannot be affected by, or affect, the resolution of a themed or density-specific
 *   resource. There is no overlay staleness to reason about here — there are no overlay folders.
 *
 * [AppLanguage.SYSTEM] means "follow the system", which is the same source the default environment
 * reads ([Locale.current]), so the empty tag simply falls through to the platform locale.
 *
 * ### Strings resolved outside a composition
 *
 * A snackbar host runs in a coroutine and resolves through [UiMessage.resolve], which cannot read a
 * composition local. To keep those messages in the same language as the screen around them,
 * [ProvideAppLocale] also publishes the language in [AppLocaleState], and [resolveLocalized] resolves
 * against it. See the note on that function for what it deliberately does *not* carry over.
 *
 * ### This file uses internal Compose Resources API, deliberately
 *
 * `ComposeEnvironment`, `LocalComposeEnvironment` and `ResourceEnvironment`'s constructor are `internal`
 * in `components-resources`, which is why this file opens with
 * `@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")`. That is not an oversight, and not how the rest
 * of this port is written. The alternatives were each worse:
 *
 * - **Public API only: there is none.** The library exposes no way to install an environment. The
 *   composition local is the only seam — and since the composable path caches its resolved string per
 *   environment, overriding the local is also the only way to get a live switch rather than strings that
 *   stay stale until the screen is rebuilt.
 * - **`java.util.Locale.setDefault(…)` plus rebuilding the subtree with `key(language)`** would work,
 *   because the default environment reads the JVM default locale and `key(…)` discards the remembered
 *   strings. Rejected: it mutates process-global state, so number, date and collation formatting for
 *   every library in the process — and every other thread — would silently change with the UI language.
 * - **Reflection** (`ResourceEnvironmentKt.getLocalComposeEnvironment()` is public in bytecode) would
 *   avoid the compiler diagnostics but trade them for silent runtime failure: a dynamic proxy to
 *   implement the environment interface, a reflective constructor call, and hand-rolled Compose group
 *   bookkeeping, because a proxy would receive the `Composer` itself.
 *
 * What the suppression costs is bounded and loud: these symbols are still checked by the compiler, so a
 * library upgrade that moves or changes them fails the build with a clear error instead of misbehaving at
 * runtime, and the headless tests assert the resolved string on both sides of a live switch.
 */
@Composable
fun ProvideAppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    val environment = remember(language) { LocaleOverrideEnvironment(language) }
    // Read by [resolveLocalized] for the coroutine path. Written after a successful composition, so a
    // failed one cannot leave the two views of "current language" disagreeing.
    SideEffect { AppLocaleState.current.value = language }
    CompositionLocalProvider(LocalComposeEnvironment provides environment) {
        content()
    }
}

/**
 * The language the last successful composition resolved as, for string resolution that happens outside a
 * composition.
 *
 * Process-wide, because that is what the callers are: a snackbar host is started from a screen but runs
 * its own coroutine, with no composition to read from. The consequence to know about is that two
 * compositions with different languages (only possible in tests) share one value — the last one to compose
 * wins — so tests that assert on this must set it, not inherit it.
 */
internal object AppLocaleState {
    val current = MutableStateFlow(AppLanguage.SYSTEM)
}

/**
 * Resolves a string resource and its format arguments in the language the UI is currently showing.
 *
 * Used by [UiMessage.resolve] for messages shown from a coroutine. [AppLanguage.SYSTEM] goes through the
 * library's own lookup, so "follow system" means exactly what it does everywhere else; an explicit
 * language resolves against the system environment with only the language swapped — the theme and density
 * qualifiers are taken from [getSystemEnvironment], the same place the default environment takes them
 * from, rather than being invented here.
 */
internal suspend fun resolveLocalized(resource: StringResource, formatArgs: Array<out Any>): String {
    val language = AppLocaleState.current.value
    val template = if (language == AppLanguage.SYSTEM) {
        getString(resource)
    } else {
        getString(environmentFor(language), resource)
    }
    return if (formatArgs.isEmpty()) template else template.format(*formatArgs)
}

/** The system environment with only the language replaced. */
private fun environmentFor(language: AppLanguage): ResourceEnvironment {
    val locale = java.util.Locale.forLanguageTag(language.languageTag)
    val system = getSystemEnvironment()
    return ResourceEnvironment(
        language = LanguageQualifier(locale.language),
        script = ScriptQualifier(locale.script),
        region = RegionQualifier(locale.country),
        theme = system.theme,
        density = system.density,
    )
}

/**
 * A [ComposeEnvironment] that answers with [language] instead of the platform locale.
 *
 * Deliberately a class rather than a lambda: [ComposeEnvironment] is a single-method interface, but that
 * method is `@Composable`, and an implementation of it cannot be written as a `fun interface` lambda
 * without losing the composition reads below.
 */
private class LocaleOverrideEnvironment(private val language: AppLanguage) : ComposeEnvironment {

    @Composable
    override fun rememberEnvironment(): ResourceEnvironment {
        val locale = language.toComposeLocale()
        val isDark = isSystemInDarkTheme()
        val density = LocalDensity.current
        return remember(locale, isDark, density) {
            ResourceEnvironment(
                language = LanguageQualifier(locale.language),
                script = ScriptQualifier(locale.script),
                region = RegionQualifier(locale.region),
                theme = ThemeQualifier.selectByValue(isDark),
                density = DensityQualifier.selectByDensity(density.density),
            )
        }
    }
}

/**
 * The locale a language setting stands for: the tag it carries, or the platform locale for
 * [AppLanguage.SYSTEM].
 *
 * An unknown tag resolves to the root locale (`java.util.Locale.forLanguageTag` never throws), whose
 * empty language/region produce an invariant qualifier — that is, the untranslated `values` strings
 * rather than a crash.
 */
private fun AppLanguage.toComposeLocale(): Locale =
    if (languageTag.isEmpty()) Locale.current else Locale(java.util.Locale.forLanguageTag(languageTag))
