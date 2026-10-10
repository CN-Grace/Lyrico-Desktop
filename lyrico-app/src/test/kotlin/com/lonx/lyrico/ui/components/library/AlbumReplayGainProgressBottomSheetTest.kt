package com.lonx.lyrico.ui.components.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_abort
import com.lonx.lyrico.resources.action_close
import com.lonx.lyrico.resources.album_replay_gain_calculating
import com.lonx.lyrico.resources.batch_replay_gain_success
import com.lonx.lyrico.resources.batch_replay_gain_total_time
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.viewmodel.AlbumActionsUiState
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 专辑 ReplayGain 的进度面板：**测量中与测量后是两种面孔**。
 *
 * 这个面板的全部内容都是状态算出来的，所以这些测试只做一件事：把状态喂进去，断言屏幕上出现的东西。真正
 * 有分歧的是那两个随 `isCalculatingAlbumReplayGain` 翻转的地方 —— 按钮（`action_abort` / `action_close`）
 * 与副标题行（"计算中" / 用时）—— 以及两个都从状态里取的计数。
 *
 * 期望文本用 [String.format] 拼，不用 `getString(res, *args)`：Compose Multiplatform 1.12 的那个 vararg
 * 重载不做 `String.format`，`%.2f` 会原样上屏（见 `formattedStringResource` 与
 * `StringFormattingGuardTest`），拿它拼期望值会让测试和坏屏幕互相印证。
 */
@OptIn(ExperimentalTestApi::class)
class AlbumReplayGainProgressBottomSheetTest {

    /** 与面板同一种用法：先取字符串，再 `String.format`。 */
    private fun text(res: StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    private fun state(
        calculating: Boolean = false,
        progress: Float? = null,
        songCount: Int = 0,
        writtenCount: Int = 0,
        totalTimeMillis: Long = 0L,
        albumName: String? = "Time Out",
    ) = AlbumActionsUiState(
        albumName = albumName,
        isCalculatingAlbumReplayGain = calculating,
        showAlbumReplayGainProgressDialog = true,
        albumReplayGainProgress = progress,
        albumReplayGainSongCount = songCount,
        albumReplayGainWrittenCount = writtenCount,
        albumReplayGainTotalTimeMillis = totalTimeMillis,
    )

    @Test
    fun `while measuring it shows the album, the percentage and how many songs were written`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                AlbumReplayGainProgressBottomSheet(
                    uiState = state(calculating = true, progress = 0.45f, songCount = 4, writtenCount = 1),
                    onDismissRequest = {},
                    onDismissFinished = {},
                    onAbort = {},
                )
            }
        }

        onNodeWithText("Time Out").assertIsDisplayed()
        onNodeWithText(text(Res.string.album_replay_gain_calculating)).assertIsDisplayed()
        onNodeWithText("45%").assertIsDisplayed()
        // 进度条的位置与计数都来自 `AlbumActionsUiState`，写出去的歌数就是用户能核对的那个数。
        onNodeWithText(text(Res.string.batch_replay_gain_success, 1)).assertIsDisplayed()
        onNodeWithText("1 / 4").assertIsDisplayed()
        // 测量中只有一个按钮，而且是中止。
        onNodeWithText(text(Res.string.action_abort)).assertIsDisplayed()
    }

    @Test
    fun `when it stops it shows the elapsed time and offers to close`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                AlbumReplayGainProgressBottomSheet(
                    uiState = state(calculating = false, progress = 1f, songCount = 4, writtenCount = 4, totalTimeMillis = 2_500L),
                    onDismissRequest = {},
                    onDismissFinished = {},
                    onAbort = {},
                )
            }
        }

        onNodeWithText(text(Res.string.batch_replay_gain_total_time, 2_500L / 1000.0)).assertIsDisplayed()
        onNodeWithText("100%").assertIsDisplayed()
        onNodeWithText("4 / 4").assertIsDisplayed()
        onNodeWithText(text(Res.string.action_close)).assertIsDisplayed()
    }

    @Test
    fun `the button aborts while measuring and closes once it stopped`() = runComposeUiTest {
        val aborted = mutableListOf<String>()
        setContent {
            LyricoTheme {
                AlbumReplayGainProgressBottomSheet(
                    uiState = state(calculating = true, progress = 0.1f, songCount = 2),
                    onDismissRequest = { aborted += "dismiss" },
                    onDismissFinished = { aborted += "finished" },
                    onAbort = { aborted += "abort" },
                )
            }
        }

        onNodeWithText(text(Res.string.action_abort)).performClick()
        assertEquals(listOf("abort"), aborted, "measuring: the button aborts, it must not try to dismiss")
    }

    @Test
    fun `a stopped sheet closes instead of aborting`() = runComposeUiTest {
        val actions = mutableListOf<String>()
        setContent {
            LyricoTheme {
                AlbumReplayGainProgressBottomSheet(
                    uiState = state(calculating = false, progress = 1f, songCount = 2, writtenCount = 2),
                    onDismissRequest = { actions += "dismiss" },
                    onDismissFinished = { actions += "finished" },
                    onAbort = { actions += "abort" },
                )
            }
        }

        onNodeWithText(text(Res.string.action_close)).performClick()
        assertEquals(listOf("dismiss"), actions, "the sheet asks its owner to close; the owner decides what happens")
    }

    @Test
    fun `a run that has not reported progress yet still renders`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                AlbumReplayGainProgressBottomSheet(
                    uiState = state(calculating = true, progress = null, albumName = null),
                    onDismissRequest = {},
                    onDismissFinished = {},
                    onAbort = {},
                )
            }
        }

        // `albumReplayGainProgress` 是可空的（测量开始前是 null），面板以 0 对待它而不是崩掉；标题也可能是
        // null（专辑没有名字），面板照样画。
        onNodeWithText("0%").assertIsDisplayed()
        // A sheet with no progress yet is still 'calculating'.
        onNodeWithText(text(Res.string.album_replay_gain_calculating)).assertIsDisplayed()
    }
}
