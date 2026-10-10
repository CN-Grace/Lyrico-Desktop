package com.lonx.lyrico.screens.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Watches a settings flow until it reports something a test is waiting for, by collecting it **once**.
 *
 * The obvious way to write this is `waitUntil { runBlocking { settings.albumSortInfo.first() == wanted } }`,
 * and that is what these tests did until it failed on a full-suite run:
 *
 * ```
 * java.io.IOException: Unable to rename ...\settings.preferences_pb.tmp to ...\settings.preferences_pb
 *   at androidx.datastore.core.FileStorageConnection.writeScope(FileStorage.kt:114)
 * ```
 *
 * Every `first()` is a fresh subscription, and a fresh subscription makes `DataStore` read its backing
 * file. Polling that way in the frame loop -- roughly once per 16 ms -- put a read in flight next to the
 * write the click had started, and on Windows the write's delete-and-rename cannot succeed while another
 * handle has the file open. The failure was in the *store*, not in the screen, and it only showed up when
 * the suite ran the tests back to back.
 *
 * One long-lived collection is both race-free and closer to what the screens do: the flow is subscribed
 * before the click, and the value the assertion needs arrives as the store's own emission. [isSatisfied]
 * reads a flag rather than the store, so polling it costs nothing and touches no file.
 */
internal class SettingsFlowWatcher<T>(
    scope: CoroutineScope,
    flow: Flow<T>,
    predicate: (T) -> Boolean,
) {
    private val matched = CompletableDeferred<T>()
    private val collection: Job = scope.launch {
        flow.collect { value ->
            if (predicate(value) && !matched.isCompleted) matched.complete(value)
        }
    }

    val isSatisfied: Boolean get() = matched.isCompleted

    /** The value that satisfied the predicate, for an assertion that wants to look at it. */
    suspend fun await(): T = matched.await()

    fun stop() {
        collection.cancel()
    }
}
