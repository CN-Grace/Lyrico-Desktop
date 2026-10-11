package com.lonx.lyrico

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.data.model.AppLanguage
import com.lonx.lyrico.data.model.ThemeConfig
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.plugin.i18n.PluginLocales
import com.lonx.lyrico.ui.ProvideAppLocale
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.theme.LyricoTheme
import org.koin.compose.koinInject

/**
 * The desktop application, replacing `LyricoApp(externalUri: Uri?, externalEditRequestId: Long)`.
 *
 * Android's external-input parameters were about receiving an `Intent`/`Uri` from the system ("open
 * with", share sheet). Windows' equivalent is a file path on the command line, so the parameter is a
 * plain [externalFilePath]. It is **still unused**: the edit-metadata screen it is meant to open is
 * ported, but nothing wires the command-line argument to it yet, so a "open with" launch opens the
 * library instead of that file. Tracked as a user-visible gap in `PLAN.md` (#37-C-2), not silently
 * dropped: the window does pass whatever `main` received.
 *
 * **This is also where the two user-facing preferences are applied**, which is the desktop equivalent of
 * what `MainActivity` did before `setContent`: it read the theme and the language out of the settings
 * store and resolved them, so that a stored choice was in force from the very first frame instead of
 * only inside the screen that happens to display it. The desktop shell collects both flows here, for the
 * same reason -- and because [LyricoTheme] and [ProvideAppLocale] have to wrap everything below them to
 * have any effect at all.
 *
 * Everything else -- data folder, DI graph, logging -- happens in `main` before the first frame, because
 * a screen that cannot resolve its dependencies should fail at startup with a clear message rather than
 * as a blank window.
 */
@Composable
fun LyricoDesktopApp(
    externalFilePath: String? = null,
    settingsRepository: SettingsRepository = koinInject(),
) {
    val themeConfig by settingsRepository.themeConfigFlow
        .collectAsStateWithLifecycle(initialValue = ThemeConfig())
    val appLanguage by settingsRepository.appLanguage
        .collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM)

    // Plugin string catalogs are resolved through their own locale list rather than through Compose, so
    // they have to be told separately; otherwise the surrounding UI would switch language while a
    // plugin's own strings stayed behind. `SYSTEM` re-reads the platform locale instead of pinning the
    // tag, so "follow system" still means "follow system" (see PluginLocales).
    LaunchedEffect(appLanguage) {
        val tag = appLanguage.languageTag
        if (tag.isEmpty()) {
            PluginLocales.initialize()
        } else {
            PluginLocales.update(java.util.Locale.forLanguageTag(tag))
        }
    }

    ProvideAppLocale(appLanguage) {
        // The ported screens were written against the Android theme, which wraps Miuix and supplies the
        // ripple indication; the port keeps that wrapper rather than theming each screen.
        LyricoTheme(
            colorMode = themeConfig.themeMode,
            keyColor = themeConfig.keyColor.color,
            monetEnabled = themeConfig.monetEnable,
        ) {
            LyricoNavHost(modifier = Modifier.fillMaxSize())
        }
    }
}
