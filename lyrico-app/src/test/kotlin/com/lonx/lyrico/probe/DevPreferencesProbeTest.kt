package com.lonx.lyrico.probe

import com.lonx.lyrico.data.model.AppLanguage
import com.lonx.lyrico.data.model.ThemeMode
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.Test

/**
 * Writes the two preferences the desktop shell applies — app language and theme mode — into the
 * development data folder, so a capture of the running window can show them.
 *
 * This is a tool, not an assertion, and it exists because of a gap between the port's phases: the shell
 * (C2) applies a stored language and theme from the very first frame, but the screen that *writes* them is
 * ported later (C3). Without this, the only way to see the shell working would be to hand-edit a protobuf.
 * It goes through the real settings store, so what it writes is exactly what a user's own change writes.
 *
 * ```
 * ./gradlew :lyrico-app:test --tests "*DevPreferencesProbeTest*" -Plyrico.devLanguage=en -Plyrico.devTheme=LIGHT
 * ./gradlew :lyrico-app:run
 * powershell -File scripts/capture-window.ps1 -TitleLike "Lyrico 1.6" -OutputPath out-en.png
 * ```
 *
 * It is skipped unless `-Plyrico.devLanguage` or `-Plyrico.devTheme` is given, because it overwrites the
 * preferences of the app's own data folder.
 */
class DevPreferencesProbeTest {

    @Test
    fun `writes the shell preferences into the development data folder`() {
        val languageName = System.getProperty("lyrico.devLanguage")
        val themeName = System.getProperty("lyrico.devTheme")
        assumeTrue(
            "needs -Plyrico.devLanguage=<tag|SYSTEM> or -Plyrico.devTheme=<ThemeMode>",
            !languageName.isNullOrBlank() || !themeName.isNullOrBlank(),
        )

        runBlocking {
            val directories = AppDirectories.resolve()
            println("PROBE dataDir=${directories.root}")

            runCatching { stopKoin() }
            startKoin { modules(desktopAppModule(directories)) }
            try {
                val settings = GlobalContext.get().get<SettingsRepository>()
                if (!languageName.isNullOrBlank()) {
                    // Accepts the enum name (`SYSTEM`) as well as the BCP 47 tag (`zh-CN`), because the tag
                    // for "follow system" is the empty string and cannot be typed as a Gradle property.
                    val language = AppLanguage.entries.firstOrNull { it.name.equals(languageName, true) }
                        ?: AppLanguage.fromLanguageTag(languageName)
                    settings.saveAppLanguage(language)
                    println("PROBE language=${language.name} tag='${settings.appLanguage.first().languageTag}'")
                }
                if (!themeName.isNullOrBlank()) {
                    val theme = ThemeMode.valueOf(themeName.uppercase())
                    settings.saveThemeMode(theme)
                    println("PROBE theme=${settings.themeMode.first()}")
                }
            } finally {
                stopKoin()
            }
        }
    }
}
