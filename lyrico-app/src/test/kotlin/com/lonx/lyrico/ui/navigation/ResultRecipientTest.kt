package com.lonx.lyrico.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.SavedStateHandle
import com.lonx.lyrico.data.model.search.LyricsSearchResult
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The receiving half of the result protocol, which the metadata editor is the only screen to use.
 *
 * Two properties are worth pinning here rather than trusting:
 *
 * 1. **Exactly once, and consumed.** `onResult` runs outside the composition, and the value has to be
 *    cleared afterwards -- otherwise returning to the editor would replay the last search result and
 *    overwrite whatever the user edited since. A *repeated* identical result must still be handled, which
 *    is why clearing matters in both directions.
 * 2. **The two halves agree.** [ResultBackNavigator] writes and [ResultRecipient] reads, they were written
 *    by different batches, and the only thing joining them is [resultKey] plus "the handle of the screen
 *    being returned to". The last test runs a real `NavHost` with the real sending half and the real
 *    receiving half, so a drift between them fails here rather than in a shipped window.
 */
class ResultRecipientTest {

    private companion object {
        const val CALLER_ROUTE = "caller"
        const val CALLEE_ROUTE = "callee"
    }

    private fun result(title: String) = LyricsSearchResult(
        title = title,
        artist = "李宗盛",
        album = null,
        lyrics = "[00:01.00]越过山丘",
        date = null,
        trackerNumber = null,
        picUrl = null,
        pluginId = "demo",
        pluginName = "Demo"
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a result is handled once, consumed, and not replayed by a recomposition`() = runComposeUiTest {
        val handle = SavedStateHandle()
        val handled = mutableListOf<LyricsSearchResult>()
        val recomposeTick = mutableStateOf(0)

        setContent {
            // Read so pressing the tick below really does recompose this subtree.
            val tick = recomposeTick.value
            Column {
                Text("tick=$tick")
                val recipient = rememberResultRecipient<LyricsSearchResult>(handle, CALLEE_ROUTE)
                recipient.onResult { handled += it }
            }
        }

        onNodeWithText("tick=0").assertExists()
        assertTrue(handled.isEmpty(), "nothing has been sent yet")

        val expected = result("山丘")
        runOnIdle { handle[resultKey(CALLEE_ROUTE)] = expected }
        waitForIdle()

        assertEquals(1, handled.size)
        assertSame(expected, handled.single(), "the instance must travel, not a copy")
        assertNull(
            handle.get<LyricsSearchResult>(resultKey(CALLEE_ROUTE)),
            "the result must be consumed, or it would be replayed on the next recomposition"
        )

        // A recomposition must not re-deliver it: the handle is empty now, so there is nothing to read.
        runOnIdle { recomposeTick.value = 1 }
        waitForIdle()
        onNodeWithText("tick=1").assertExists()
        assertEquals(1, handled.size, "a stale value must not be replayed")

        // A *new* (equal) result must still fire, so consumption must not be one-shot for the screen's
        // whole lifetime -- the user can search twice and pick the same result twice.
        val again = result("山丘")
        runOnIdle { handle[resultKey(CALLEE_ROUTE)] = again }
        waitForIdle()
        assertEquals(2, handled.size, "a repeated value is a new result")
        assertSame(again, handled.last())
        assertNull(handle.get<LyricsSearchResult>(resultKey(CALLEE_ROUTE)))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `results are namespaced by the sending route`() = runComposeUiTest {
        val handle = SavedStateHandle()
        val fromLyricsSearch = AtomicInteger()
        val fromSongSearch = AtomicInteger()

        setContent {
            rememberResultRecipient<LyricsSearchResult>(handle, SearchLyricsDestination.BASE)
                .onResult { fromLyricsSearch.incrementAndGet() }
            rememberResultRecipient<LyricsSearchResult>(handle, SearchResultsDestination.BASE)
                .onResult { fromSongSearch.incrementAndGet() }
            Text("recipients")
        }
        onNodeWithText("recipients").assertExists()

        // The editor receives a result from both of these routes, so they must not collide.
        runOnIdle { handle[resultKey(SearchLyricsDestination.BASE)] = result("来自歌词搜索") }
        waitForIdle()
        assertEquals(1, fromLyricsSearch.get())
        assertEquals(0, fromSongSearch.get(), "the other sender must not fire")

        runOnIdle { handle[resultKey(SearchResultsDestination.BASE)] = result("来自歌曲搜索") }
        waitForIdle()
        assertEquals(1, fromSongSearch.get())
        assertEquals(1, fromLyricsSearch.get())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the sending and receiving halves agree through a real navigation graph`() = runComposeUiTest {
        val expected = result("山丘")
        val controllerRef = AtomicReference<NavHostController>()
        val senderRef = AtomicReference<ResultBackNavigator<LyricsSearchResult>>()
        val handled = mutableListOf<LyricsSearchResult>()

        setContent {
            val controller = rememberNavController()
            controllerRef.set(controller)
            NavHost(navController = controller, startDestination = CALLER_ROUTE) {
                composable(CALLER_ROUTE) { entry ->
                    // Exactly how `EditMetadataScreen` receives: one recipient per sending route, built
                    // from this entry's own handle, and `onResult` in the body.
                    rememberResultRecipient<LyricsSearchResult>(entry.savedStateHandle, CALLEE_ROUTE)
                        .onResult { handled += it }
                    Text("caller")
                }
                composable(CALLEE_ROUTE) {
                    senderRef.set(
                        rememberResultBackNavigator<LyricsSearchResult>(
                            controller = controller,
                            route = CALLEE_ROUTE
                        )
                    )
                    Text("callee")
                }
            }
        }

        onNodeWithText("caller").assertExists()
        // Driven from `runOnIdle`: the navigation library moves lifecycles on its own dispatcher, and a
        // synthesized click lands off it (measured -- see `ResultBackNavigatorTest`).
        runOnIdle { controllerRef.get().navigate(CALLEE_ROUTE) }
        onNodeWithText("callee").assertExists()

        runOnIdle { senderRef.get().navigateBack(expected) }

        onNodeWithText("caller").assertExists()
        onNodeWithText("callee").assertDoesNotExist()
        assertEquals(1, handled.size)
        assertSame(expected, handled.single())
    }
}
