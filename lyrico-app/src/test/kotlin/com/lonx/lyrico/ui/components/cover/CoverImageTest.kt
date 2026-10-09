package com.lonx.lyrico.ui.components.cover

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.ui.theme.LyricoTheme
import kotlin.test.Test

/**
 * The "no artwork" branch of [CoverImage].
 *
 * The loading branch is covered by `CoverPipelineTest`, which decodes a real cover off a real file;
 * what is asserted here is the rule that decides whether a request is made at all — a song with no
 * path and no artist must show the placeholder immediately instead of an empty box, which is what a
 * user sees for every file that has no embedded picture.
 */
@OptIn(ExperimentalTestApi::class)
class CoverImageTest {

    @Test
    fun `a song with no artwork shows the placeholder`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                CoverImage(
                    uri = null,
                    lastModified = 0L,
                    contentDescription = "cover placeholder",
                )
            }
        }

        onNodeWithContentDescription("cover placeholder").assertIsDisplayed()
    }
}
