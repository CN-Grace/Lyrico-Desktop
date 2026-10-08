package com.lonx.lyrico

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lonx.audiotag.internal.NativeLibraryLoader
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Desktop entry point (replaces the Android `MainActivity`).
 *
 * Phase 2 of the port only has to prove that a real window opens and that the Miuix desktop
 * artifacts render with the same theme the Android app used. The screen below is therefore a
 * status report rather than a feature: it exercises a Miuix top bar, card, text styles and a
 * button, and it reports whether the phase-1 native libraries actually load inside the real
 * application JVM.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Lyrico ${BuildInfo.VERSION_NAME} (${BuildInfo.COMMIT})",
        state = rememberWindowState(width = 1180.dp, height = 780.dp),
    ) {
        // Same Miuix theme controller the Android app used in ui/theme/Theme.kt. The
        // CompositionLocalProvider(ripple) part of it is Android-specific and arrives with the
        // theme migration in phase 4.
        MiuixTheme(controller = ThemeController(ColorSchemeMode.System)) {
            PortStatusScreen(onExit = ::exitApplication)
        }
    }
}

@Composable
internal fun PortStatusScreen(onExit: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            SmallTopAppBar(
                color = Color.Transparent,
                defaultWindowInsetsPadding = false,
                title = "Lyrico 桌面移植",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(bottom = 24.dp),
        ) {
            SmallTitle(text = "P2 门禁：Compose Desktop + Miuix 桌面构件")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                BasicComponent(title = "应用版本", summary = VersionSummary)
                BasicComponent(title = "构建类型", summary = BuildInfo.BUILD_TYPE)
                BasicComponent(title = "运行环境", summary = RuntimeSummary)
                BasicComponent(title = "操作系统", summary = osSummary())
            }

            SmallTitle(text = "P1 原生库（skipped if unavailable）")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                BasicComponent(title = "taglib.dll", summary = nativeLibraryStatus())
            }

            SmallTitle(text = "文本与字体")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    text = "中文简体 中文繁體 日本語 한국어 — 字体回退检查 ABCdef123",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Miuix 主题色 / 卡片 / 顶栏 / 文本样式均已渲染",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                text = "退出",
                onClick = onExit,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

private val VersionSummary: String =
    "${BuildInfo.VERSION_NAME}(${BuildInfo.VERSION_CODE}) · ${BuildInfo.COMMIT}"

private val RuntimeSummary: String = buildString {
    append("JVM ").append(System.getProperty("java.version"))
    append(" · ").append(System.getProperty("java.vendor"))
    System.getProperty("lyrico.version")?.let { append(" · lyrico.version=").append(it) }
}

private fun osSummary(): String =
    "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}"

/** Loads the phase-1 native bridge inside the real application JVM, once per window. */
@Composable
private fun nativeLibraryStatus(): String = remember {
    runCatching { NativeLibraryLoader.load() }.fold(
        onSuccess = {
            val dir = System.getProperty(NativeLibraryLoader.NATIVE_DIR_PROPERTY) ?: "java.library.path"
            "已加载 · $dir"
        },
        onFailure = { error -> "加载失败 · ${error.message}" },
    )
}
