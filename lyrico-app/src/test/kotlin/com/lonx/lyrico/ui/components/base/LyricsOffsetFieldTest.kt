package com.lonx.lyrico.ui.components.base

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_manual_input
import com.lonx.lyrico.resources.action_reset
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.lyrics_offset_input_hint
import com.lonx.lyrico.ui.theme.LyricoTheme
import com.lonx.lyrico.utils.formattedStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lyrics-offset stepper: the control that moves subtitles in time, exercised through its buttons.
 *
 * The interesting cases are at the edges. A step is *clamped* rather than refused, so the last click
 * before a limit still reports the limit, while at the limit the direction is switched off entirely --
 * which is why the limit test checks the button's enabled state as well as the value. [formatLyricsOffset]
 * is what the stepper announces to a screen reader, so its sign rule is pinned here too: a wrong sign in
 * the middle of the range is exactly the kind of bug no screenshot would show.
 *
 * The manual-input dialog is a composable in its own right, so it is tested directly: it takes an
 * absolute value plus a sign toggle, and a value outside the range must not be confirmable.
 */
@OptIn(ExperimentalTestApi::class)
class LyricsOffsetFieldTest {

    @Test
    fun `the stepper announces the offset it is showing`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                LyricsOffsetField(offset = -300L, onOffsetChange = {})
            }
        }

        onNodeWithContentDescription("-300ms").assertIsDisplayed()
    }

    @Test
    fun `formatting is signed only when it has to be`() {
        assertEquals("+100ms", formatLyricsOffset(100L))
        assertEquals("-100ms", formatLyricsOffset(-100L))
        assertEquals("0ms", formatLyricsOffset(0L), "zero is not a positive offset")
    }

    @Test
    fun `the plus button reports one step up`() = runComposeUiTest {
        var reported: Long? = null
        setContent {
            LyricoTheme {
                LyricsOffsetField(offset = 0L, onOffsetChange = { reported = it })
            }
        }

        onNodeWithText("+100ms").performClick()

        runOnIdle { assertEquals(100L, reported) }
    }

    @Test
    fun `the minus button reports one step down`() = runComposeUiTest {
        var reported: Long? = null
        setContent {
            LyricoTheme {
                LyricsOffsetField(offset = 0L, onOffsetChange = { reported = it })
            }
        }

        onNodeWithText("-100ms").performClick()

        runOnIdle { assertEquals(-100L, reported) }
    }

    @Test
    fun `the last step before the limit reports the limit`() = runComposeUiTest {
        var reported: Long? = null
        setContent {
            LyricoTheme {
                LyricsOffsetField(offset = 9_950L, onOffsetChange = { reported = it })
            }
        }

        onNodeWithText("+100ms").performClick()

        runOnIdle { assertEquals(10_000L, reported, "a step is clamped to the maximum, not dropped") }
    }

    @Test
    fun `at the limit the direction is switched off`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                LyricsOffsetField(offset = 10_000L, onOffsetChange = {})
            }
        }

        onNodeWithText("+100ms").assertIsNotEnabled()
        onNodeWithText("-100ms").assertIsDisplayed()
    }

    @Test
    fun `reset is offered only when there is an offset to reset`() = runComposeUiTest {
        var reported: Long? = null
        var resetLabel = ""
        setContent {
            LyricoTheme {
                resetLabel = stringResource(Res.string.action_reset)
                LyricsOffsetField(offset = -300L, onOffsetChange = { reported = it })
            }
        }

        onNodeWithText(resetLabel).performClick()

        runOnIdle { assertEquals(0L, reported) }
    }

    @Test
    fun `reset is disabled when the offset is already zero`() = runComposeUiTest {
        var reported: Long? = null
        var resetLabel = ""
        setContent {
            LyricoTheme {
                resetLabel = stringResource(Res.string.action_reset)
                LyricsOffsetField(offset = 0L, onOffsetChange = { reported = it })
            }
        }

        onNodeWithText(resetLabel).assertIsNotEnabled()
        runOnIdle { assertNull(reported) }
    }

    @Test
    fun `an offset outside the range is shown as the limit it is clamped to`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                LyricsOffsetField(offset = 99_999L, onOffsetChange = {})
            }
        }

        onNodeWithContentDescription("+10000ms").assertIsDisplayed()
    }

    @Test
    fun `the manual input action opens the dialog on the current offset`() = runComposeUiTest {
        var manualInputLabel = ""
        setContent {
            LyricoTheme {
                manualInputLabel = stringResource(Res.string.action_manual_input)
                var offset by mutableStateOf(-100L)
                LyricsOffsetField(offset = offset, onOffsetChange = { offset = it })
            }
        }

        onNodeWithText(manualInputLabel).performClick()

        // The dialog's sign toggle reads its state from the offset the field was showing.
        onNodeWithText("−").assertIsDisplayed()
    }

    @Test
    fun `the range hint is formatted, not passed through as a placeholder`() = runComposeUiTest {
        // The dialog's own hint must equal what the project's formatting helper produces. Building the
        // expectation with the same library call the UI uses would agree with a broken UI; the helper
        // is the independent side of this comparison.
        var expected = ""
        setContent {
            LyricoTheme {
                expected = formattedStringResource(Res.string.lyrics_offset_input_hint, -10_000L, 10_000L)
                LyricsOffsetInputDialog(
                    show = true,
                    initialValue = 0L,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        assertTrue(expected.isNotBlank())
        assertFalse(expected.contains("%"), "the test's own expectation is a placeholder: $expected")
        onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun `the manual input dialog confirms the value it was opened with`() = runComposeUiTest {
        var confirmed: Long? = null
        var confirmLabel = ""
        setContent {
            LyricoTheme {
                confirmLabel = stringResource(Res.string.confirm)
                LyricsOffsetInputDialog(
                    show = true,
                    initialValue = -250L,
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                )
            }
        }

        onNodeWithText(confirmLabel).performClick()

        runOnIdle { assertEquals(-250L, confirmed, "the sign of the initial value must be kept") }
    }

    @Test
    fun `the manual input dialog flips the sign when the sign button is used`() = runComposeUiTest {
        var confirmed: Long? = null
        var confirmLabel = ""
        setContent {
            LyricoTheme {
                confirmLabel = stringResource(Res.string.confirm)
                LyricsOffsetInputDialog(
                    show = true,
                    initialValue = -250L,
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                )
            }
        }

        onNodeWithText("−").performClick() // -250 -> +250
        onNodeWithText("+").assertIsDisplayed()
        onNodeWithText(confirmLabel).performClick()

        runOnIdle { assertEquals(250L, confirmed) }
    }

    @Test
    fun `the manual input dialog refuses a value outside the range`() = runComposeUiTest {
        var confirmed: Long? = null
        var confirmLabel = ""
        setContent {
            LyricoTheme {
                confirmLabel = stringResource(Res.string.confirm)
                LyricsOffsetInputDialog(
                    show = true,
                    initialValue = 0L,
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                )
            }
        }

        // The dialog has exactly one editable field; 99999 is over the 10000ms maximum.
        onNode(hasSetTextAction()).performTextReplacement("99999")

        onNodeWithText(confirmLabel).assertIsNotEnabled()
        runOnIdle { assertNull(confirmed) }
    }
}
