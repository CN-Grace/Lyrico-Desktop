package com.lonx.lyrico.ui.components.bar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_close
import com.lonx.lyrico.resources.action_deselect_all
import com.lonx.lyrico.resources.action_select_all
import com.lonx.lyrico.resources.selection_mode_selected_count
import com.lonx.lyrico.ui.theme.LyricoTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The selection bar, rendered headlessly at two widths.
 *
 * Two things are worth asserting here rather than at the screen level. The first is the label *flip*:
 * the bar shows "select all" or "deselect all" depending on whether the visible songs are all
 * selected, and that comparison is made inside the bar; driving it through the callback and the
 * recomposition is what proves the branch exists, where asserting only the callback would not. The
 * second is the compact rule — below 360 dp the select-all label is dropped, because three labels do
 * not fit next to each other in a narrow window. That rule lives in the bar, so it is tested there by
 * constraining the width rather than by resizing a window.
 *
 * All labels are read through the same resource path the bar uses, so nothing here depends on the
 * machine's locale.
 */
@OptIn(ExperimentalTestApi::class)
class SongSelectionTopAppBarTest {

    private fun song(name: String) = SongEntity(
        folderId = 1L,
        mediaId = 0,
        source = SongSource.LOCAL,
        filePath = """H:\Music\$name""",
        fileName = name,
        uri = """H:\Music\$name""",
        title = name,
    )

    private val songs = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))

    /** Formatted with [String.format]; see `StringFormattingGuardTest` for why not `getString(res, *args)`. */
    private fun text(res: org.jetbrains.compose.resources.StringResource, vararg args: Any): String =
        String.format(runBlocking { getString(res) }, *args)

    /** The bar is width-sensitive, so every case states the width it is being measured at. */
    @Composable
    private fun BarAt(
        width: Dp,
        selected: Set<String>,
        onSelectAll: (List<SongEntity>) -> Unit = {},
        onDeselectAll: () -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        val scrollBehavior = MiuixScrollBehavior()
        Box(Modifier.width(width)) {
            SongSelectionTopAppBar(
                songs = songs,
                selectedSongUris = selected,
                scrollBehavior = scrollBehavior,
                applyInsets = false,
                onSelectAll = onSelectAll,
                onDeselectAll = onDeselectAll,
                onClose = onClose,
            )
        }
    }

    @Test
    fun `a partial selection offers select all, a full one offers deselect all`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                var selected by remember { mutableStateOf(setOf(songs[0].uri)) }
                BarAt(
                    width = 500.dp,
                    selected = selected,
                    onSelectAll = { selected = it.map { song -> song.uri }.toSet() },
                    onDeselectAll = { selected = emptySet() },
                )
            }
        }

        onNodeWithText(text(Res.string.action_select_all)).assertIsDisplayed()
        onNodeWithText(text(Res.string.action_deselect_all)).assertDoesNotExist()

        onNodeWithText(text(Res.string.action_select_all)).performClick()

        onNodeWithText(text(Res.string.action_deselect_all)).assertIsDisplayed()
        onNodeWithText(text(Res.string.action_select_all)).assertDoesNotExist()

        onNodeWithText(text(Res.string.action_deselect_all)).performClick()

        onNodeWithText(text(Res.string.action_select_all)).assertIsDisplayed()
    }

    @Test
    fun `the bar shows how many songs are selected`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                BarAt(width = 500.dp, selected = setOf(songs[0].uri, songs[1].uri))
            }
        }

        onNodeWithText(text(Res.string.selection_mode_selected_count, 2)).assertIsDisplayed()
    }

    @Test
    fun `selecting everything in the list is what flips the label`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                // Selected count is full but computed against a *different* list: the bar has to compare
                // against the songs it was given, so this stays on "select all".
                BarAt(
                    width = 500.dp,
                    selected = setOf("""H:\Music\other-1.mp3""", """H:\Music\other-2.mp3""", """H:\Music\other-3.mp3"""),
                )
            }
        }

        onNodeWithText(text(Res.string.action_select_all)).assertIsDisplayed()
        onNodeWithText(text(Res.string.action_deselect_all)).assertDoesNotExist()
    }

    @Test
    fun `a narrow window keeps close but drops select all`() = runComposeUiTest {
        setContent {
            LyricoTheme {
                BarAt(width = 320.dp, selected = setOf(songs[0].uri))
            }
        }

        onNodeWithText(text(Res.string.action_close)).assertIsDisplayed()
        onNodeWithText(text(Res.string.action_select_all)).assertDoesNotExist()
    }

    @Test
    fun `a wide window keeps both labels`() = runComposeUiTest {
        var closed = false
        setContent {
            LyricoTheme {
                BarAt(width = 500.dp, selected = setOf(songs[0].uri), onClose = { closed = true })
            }
        }

        onNodeWithText(text(Res.string.action_select_all)).assertIsDisplayed()
        onNodeWithText(text(Res.string.action_close)).performClick()

        runOnIdle { assertTrue(closed, "closing the bar has to reach the caller: it is how selection mode ends") }
    }

    @Test
    fun `an empty selection never claims everything is selected`() = runComposeUiTest {
        var selectedAll: List<SongEntity>? = null
        setContent {
            LyricoTheme {
                BarAt(width = 500.dp, selected = emptySet(), onSelectAll = { selectedAll = it })
            }
        }

        onNodeWithText(text(Res.string.action_select_all)).performClick()

        runOnIdle {
            assertEquals(
                songs.map { it.uri },
                selectedAll?.map { it.uri },
                "select-all has to hand over the songs it is showing, or the caller cannot select anything",
            )
        }
    }
}
