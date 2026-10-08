package com.lonx.lyrico

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.audiotag.internal.NativeLibraryLoader
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2 gate, in testable form.
 *
 * `scripts/capture-window.ps1` proves that a window actually opens on the Windows desktop; this test
 * proves *what* is inside it: the composables build against the real Miuix `-desktop` artifacts and
 * the phase-1 native bridge loads in an ordinary JVM (the `run` task's JVM is a different one).
 */
class PortStatusShellTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `shell renders miiux top bar, cards and text`() = runComposeUiTest {
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.Light)) {
                PortStatusScreen(onExit = {})
            }
        }

        // Top app bar + section titles (Miuix Scaffold / SmallTitle / SmallTopAppBar).
        onNodeWithText("Lyrico 桌面移植").assertExists()
        onNodeWithText("P2 门禁：Compose Desktop + Miuix 桌面构件").assertExists()
        onNodeWithText("P1 原生库（skipped if unavailable）").assertExists()
        onNodeWithText("文本与字体").assertExists()
        // Card content (Miuix BasicComponent rows) + button.
        onNodeWithText("应用版本").assertExists()
        onNodeWithText("运行环境").assertExists()
        onNodeWithText("taglib.dll").assertExists()
        onNodeWithText("退出").assertExists()
        // CJK + Hangul + Latin in one string: the string must survive the whole pipeline (Kotlin
        // source encoding → compose resources → semantics). Glyph fallback itself is checked by the
        // window screenshot, not here.
        onNodeWithText("中文简体 中文繁體 日本語 한국어 — 字体回退检查 ABCdef123").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `native bridge reports loaded inside the app jvm`() = runComposeUiTest {
        setContent {
            MiuixTheme(controller = ThemeController(ColorSchemeMode.Light)) {
                PortStatusScreen(onExit = {})
            }
        }

        // On failure the row reads "加载失败 · …", which does not contain this substring, so a broken
        // DLL turns into a test failure instead of a silently-rendered error row.
        onNodeWithText("已加载", substring = true).assertExists()
    }

    @Test
    fun `phase 1 native libraries load through lyrico native dir`() {
        val result = runCatching { NativeLibraryLoader.load() }
        assertTrue(
            result.isSuccess,
            "native library load failed: ${result.exceptionOrNull()?.stackTraceToString()}",
        )
        val dir = System.getProperty(NativeLibraryLoader.NATIVE_DIR_PROPERTY)
        println("[P2] native dir = $dir")
        assertEquals(
            "windows-x64",
            java.io.File(dir.orEmpty()).name,
            "the test JVM must be pointed at build/native/windows-x64",
        )
    }
}
