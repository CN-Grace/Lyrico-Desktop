package com.lonx.lyrico.utils

import com.lonx.lyrico.BuildInfo
import com.lonx.lyrico.data.dto.ReleaseInfo
import com.lonx.lyrico.data.model.UpdateCheckResult
import com.lonx.lyrico.data.repository.UpdateRepository
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.update_already_latest
import com.lonx.lyrico.resources.update_api_error
import com.lonx.lyrico.resources.update_network_error
import com.lonx.lyrico.resources.update_parse_error
import com.lonx.lyrico.resources.update_timeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The update check's state machine, driven through a fake repository.
 *
 * [UpdateManagerImpl] is a mapping: one `UpdateCheckResult` in, one `UpdateState`/`UpdateEffect`
 * out. `UpdateRepositoryImplTest` already proves the repository reads GitHub correctly over a real
 * local HTTP server; what is left to pin here is the mapping itself — in particular the two things a
 * user notices and a developer gets wrong:
 *
 * 1. **A failure must not be silent or mislabelled.** Every one of the five failure results has its
 *    own message, so "GitHub answered 404" cannot surface as "you are offline".
 * 2. **The check must be re-entrant-safe.** `checkForUpdate` is called from a menu item, so it must
 *    refuse to start a second request while one is in flight, or a slow network turns one click into
 *    three requests and three toasts.
 *
 * The fake repository is the seam: it is the only way to make the network answer "timeout" or
 * "malformed JSON" on demand, and those branches are precisely what the Android app could not test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {

    private val release = ReleaseInfo(
        versionName = "9.9.9",
        releaseNotes = "notes",
        url = "https://example.invalid/release",
    )

    /** Answers with a result chosen by the test, and records what it was asked for. */
    private class FakeUpdateRepository(
        private val result: UpdateCheckResult,
    ) : UpdateRepository {
        var calls = 0
            private set
        var lastOwner: String? = null
            private set
        var lastRepo: String? = null
            private set

        override suspend fun checkForUpdate(owner: String, repo: String): UpdateCheckResult {
            calls++
            lastOwner = owner
            lastRepo = repo
            return result
        }
    }

    /**
     * `appScope` is deliberately a *separate* scope sharing this test's scheduler: unconfined, so a
     * launched check runs eagerly instead of needing an explicit `advanceUntilIdle` at every call
     * site, and detached from `runTest`'s own scope, so the never-returning check in the
     * re-entrancy test cannot make `runTest` wait for a coroutine that is designed never to finish.
     */
    private fun TestScope.newManager(result: UpdateCheckResult): Pair<UpdateManagerImpl, FakeUpdateRepository> {
        val repository = FakeUpdateRepository(result)
        val manager = UpdateManagerImpl(
            appScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            updateRepository = repository,
        )
        return manager to repository
    }

    /**
     * Collects `effect` before the call under test.
     *
     * `effect` is a `MutableSharedFlow` with no replay: emitting with nobody subscribed drops the
     * message, so reading `effect.first()` *after* `checkForUpdate()` either hangs the test or
     * silently passes. The app always has a collector mounted (`LaunchedEffect`), so subscribing
     * first is also what the real wiring does. `backgroundScope` is cancelled by `runTest` on the
     * way out, which matters because a collector on a `SharedFlow` never completes by itself.
     */
    private fun TestScope.collectEffects(manager: UpdateManager): MutableList<UpdateEffect> {
        val received = mutableListOf<UpdateEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.effect.collect { received += it }
        }
        return received
    }

    @Test
    fun `a new version becomes the dialog state and no message`() = runTest {
        val (manager, repository) = newManager(UpdateCheckResult.NewVersion(release))

        manager.checkForUpdate()

        assertEquals(release, manager.state.value.releaseInfo)
        assertFalse(manager.state.value.isChecking)
        assertEquals(1, repository.calls)
    }

    @Test
    fun `the check asks the repository for this fork, not the android upstream`() = runTest {
        // The update source is a build-time property now. If it ever drifts back to the Android
        // repository, the app offers an .apk it cannot install -- so the constant is pinned here.
        val (manager, repository) = newManager(UpdateCheckResult.NoUpdateAvailable)

        manager.checkForUpdate()

        assertEquals(BuildInfo.UPDATE_OWNER, repository.lastOwner)
        assertEquals(BuildInfo.UPDATE_REPO, repository.lastRepo)
        assertEquals("CN-Grace", repository.lastOwner)
        assertEquals("Lyrico-Desktop", repository.lastRepo)
    }

    @Test
    fun `every failure result has its own message`() = runTest {
        val cases = listOf(
            UpdateCheckResult.NoUpdateAvailable to Res.string.update_already_latest,
            UpdateCheckResult.ApiError(404, "Not Found") to Res.string.update_api_error,
            UpdateCheckResult.NetworkError(IOException("offline")) to Res.string.update_network_error,
            UpdateCheckResult.ParsingError to Res.string.update_parse_error,
            UpdateCheckResult.TimeoutError to Res.string.update_timeout,
        )

        cases.forEach { (result, expected) ->
            val (manager, _) = newManager(result)
            val effects = collectEffects(manager)

            manager.checkForUpdate()

            assertEquals(expected, effects.single().messageRes, "for $result")
            assertNull(manager.state.value.releaseInfo, "a failure must not open the dialog")
            assertFalse(manager.state.value.isChecking, "the spinner must stop, for $result")
        }
    }

    @Test
    fun `a second check while one is in flight is ignored`() = runTest {
        // A repository that never returns: the first check stays "in flight" for the whole test.
        val repository = object : UpdateRepository {
            var calls = 0
            override suspend fun checkForUpdate(owner: String, repo: String): UpdateCheckResult {
                calls++
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val manager = UpdateManagerImpl(
            appScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            updateRepository = repository,
        )

        manager.checkForUpdate()
        assertTrue(manager.state.value.isChecking, "the first check must be in flight")
        manager.checkForUpdate()
        manager.checkForUpdate()

        assertEquals(1, repository.calls)
    }

    @Test
    fun `a new version can be dismissed and the state reset`() = runTest {
        val (manager, _) = newManager(UpdateCheckResult.NewVersion(release))

        manager.checkForUpdate()
        manager.dismissUpdateDialog()

        assertNull(manager.state.value.releaseInfo)

        manager.checkForUpdate()
        manager.resetUpdateState()

        assertNull(manager.state.value.releaseInfo)
        assertFalse(manager.state.value.isChecking)
    }
}
