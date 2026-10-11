package com.lonx.lyrico.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.ui.theme.LyricoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The page dots, including the two rules that are easy to lose in a port because nothing draws them:
 * a single page shows no dots at all, and the dot for the page you are already on is not clickable.
 */
@OptIn(ExperimentalTestApi::class)
class PagerDotsIndicatorTest {

    @Test
    fun `a single page shows no dots`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                PagerDotsIndicator(pageCount = 1, currentPage = 0, pageDescriptions = listOf("cover"))
            }
        }

        onNodeWithContentDescription("cover").assertDoesNotExist()
    }

    @Test
    fun `every page gets a dot that names it`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                PagerDotsIndicator(
                    pageCount = 3,
                    currentPage = 1,
                    pageDescriptions = listOf("cover", "lyrics", "artist"),
                )
            }
        }

        onNodeWithContentDescription("cover").assertIsDisplayed()
        onNodeWithContentDescription("lyrics").assertIsDisplayed()
        onNodeWithContentDescription("artist").assertIsDisplayed()
    }

    @Test
    fun `tapping a dot selects that page`() = runComposeUiTest {
        var selected = -1
        setContent {
            LyricoTheme {
                PagerDotsIndicator(
                    pageCount = 3,
                    currentPage = 0,
                    pageDescriptions = listOf("cover", "lyrics", "artist"),
                    onPageClick = { selected = it },
                )
            }
        }

        onNodeWithContentDescription("artist").performClick()

        runOnIdle { assertEquals(2, selected, "the dot index is the page index") }
    }

    @Test
    fun `the dot of the page being shown is not clickable`() = runComposeUiTest {
        var selected = -1
        setContent {
            LyricoTheme {
                PagerDotsIndicator(
                    pageCount = 3,
                    currentPage = 1,
                    pageDescriptions = listOf("cover", "lyrics", "artist"),
                    onPageClick = { selected = it },
                )
            }
        }

        // Not enabled rather than "clicked and ignored": the active dot offers no action at all.
        onNodeWithContentDescription("lyrics").assertIsNotEnabled()
        onNodeWithContentDescription("cover").assertIsEnabled()
        runOnIdle { assertEquals(-1, selected) }
    }
}
