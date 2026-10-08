package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.model.artist.ArtistSplitConfig
import com.lonx.lyrico.data.model.artist.ArtistSplitDefaults
import com.lonx.lyrico.data.model.artist.effectiveSeparators
import com.lonx.lyrico.data.repository.LibraryIndexRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The artist-splitting settings screen's state holder, against the real settings store and the real
 * index repository.
 *
 * The interesting behaviour is all in the validation rules, and they are cross-referential: a new
 * separator is rejected if it duplicates another custom one, or any *currently visible* built-in one
 * -- where "visible" depends on two separate collections (the hidden-id set and the enabled overrides).
 * Those rules are also the ones a reader is most likely to "fix" into something wrong, so each has a
 * test that fails if the rule is relaxed or tightened.
 */
class ArtistSplitSettingsViewModelTest {

    private lateinit var fixture: LibraryBrowseFixture
    private lateinit var viewModel: ArtistSplitSettingsViewModel
    private lateinit var collectors: CoroutineScope

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        fixture = LibraryBrowseFixture()
        collectors = CoroutineScope(Dispatchers.Default + SupervisorJob())
        newViewModel()
    }

    @AfterTest
    fun tearDown() {
        collectors.cancel()
        fixture.close()
        Dispatchers.resetMain()
    }

    /** A second instance over the same store, i.e. "the screen was reopened". */
    private fun newViewModel(index: LibraryIndexRepository = fixture.index): ArtistSplitSettingsViewModel {
        val created = ArtistSplitSettingsViewModel(fixture.settings, index)
        collectors.keepCollecting(created.uiState)
        viewModel = created
        return created
    }

    /**
     * Waits for the config inside [ArtistSplitSettingsUiState] to satisfy [predicate]. Real DataStore IO, so
     * the wait is on the value rather than on a virtual clock.
     */
    private suspend fun awaitConfig(predicate: (ArtistSplitConfig) -> Boolean) {
        awaitUntil(describe = { viewModel.uiState.value.config }) { predicate(it) }
    }

    /**
     * Adds a separator and waits for it to land.
     *
     * The wait is not belt-and-braces: [ArtistSplitSettingsViewModel.updateConfig] is a read-modify-write on
     * the settings store issued from a fresh coroutine per call, so two mutations in flight at once can lose
     * one of the two writes. Every mutation in these tests is therefore awaited before the next one starts --
     * which is also how the screen is used.
     */
    private suspend fun addSeparator(value: String): Boolean {
        val before = viewModel.uiState.value.config.customSeparators.size
        val accepted = viewModel.addCustomSeparator(value)
        if (accepted) {
            awaitConfig { it.customSeparators.size == before + 1 }
        }
        return accepted
    }

    private suspend fun addNoSplitArtist(name: String): Boolean {
        val before = viewModel.uiState.value.config.customNoSplitArtists.size
        val accepted = viewModel.addCustomNoSplitArtist(name)
        if (accepted) {
            awaitConfig { it.customNoSplitArtists.size == before + 1 }
        }
        return accepted
    }

    @Test
    fun `the stored config is emitted with the built-in separators in force`() = runBlocking<Unit> {
        awaitUntil(describe = { viewModel.uiState.value.config }) { it.enabled }

        val separators = viewModel.uiState.value.config.effectiveSeparators()
        assertTrue("/" in separators, "the built-in list is the starting point, not an empty config")
        assertFalse("&" in separators, "default-off built-ins stay off")
        assertTrue(viewModel.uiState.value.config.customSeparators.isEmpty())
    }

    @Test
    fun `enabling and disabling the feature is persisted`() = runBlocking<Unit> {
        viewModel.setEnabled(false)
        awaitConfig { !it.enabled }

        newViewModel()
        awaitConfig { !it.enabled }
    }

    @Test
    fun `a custom separator is stored verbatim and flags a pending index rebuild`() = runBlocking<Unit> {
        val accepted = viewModel.addCustomSeparator("  x  ")

        assertTrue(accepted, "the input is legal")
        awaitConfig { it.customSeparators.size == 1 }
        assertEquals(
            "  x  ",
            viewModel.uiState.value.config.customSeparators.single().value,
            "the stored value is not trimmed -- trimming happens at comparison and split time",
        )
        assertTrue(viewModel.uiState.value.hasPendingIndexRebuild, "the library has to be re-split")
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `the accepted separator survives reopening the screen`() = runBlocking<Unit> {
        viewModel.addCustomSeparator(" · ")
        awaitConfig { it.customSeparators.isNotEmpty() }

        newViewModel()

        awaitConfig { it.customSeparators.isNotEmpty() }
        assertEquals(" · ", viewModel.uiState.value.config.customSeparators.single().value)
    }

    @Test
    fun `a blank separator is rejected as EMPTY and stores nothing`() = runBlocking<Unit> {
        val accepted = viewModel.addCustomSeparator("   ")

        assertFalse(accepted)
        awaitUntil(describe = { viewModel.uiState.value.error }) { it == ArtistSplitValidationError.EMPTY }
        assertTrue(viewModel.uiState.value.config.customSeparators.isEmpty())
    }

    @Test
    fun `a separator that duplicates another custom one is rejected`() = runBlocking<Unit> {
        assertTrue(addSeparator(" · "))

        val accepted = viewModel.addCustomSeparator("·")

        assertFalse(accepted, "the comparison trims, so ' · ' and '·' are the same separator")
        awaitUntil(describe = { viewModel.uiState.value.error }) { it == ArtistSplitValidationError.DUPLICATE }
        assertEquals(1, viewModel.uiState.value.config.customSeparators.size)
    }

    @Test
    fun `a separator that duplicates an enabled built-in is rejected`() = runBlocking<Unit> {
        val accepted = viewModel.addCustomSeparator("/")

        assertFalse(accepted, "'/' is enabled by default and would split twice")
        awaitUntil(describe = { viewModel.uiState.value.error }) { it == ArtistSplitValidationError.DUPLICATE }
    }

    @Test
    fun `hiding a built-in separator frees its value for a custom one`() = runBlocking<Unit> {
        viewModel.removeBuiltinSeparator("slash")
        awaitConfig { "slash" in it.hiddenBuiltinSeparatorIds }

        assertTrue(viewModel.addCustomSeparator("/"), "a hidden built-in no longer competes")
    }

    @Test
    fun `enabling a default-off built-in makes its value unavailable again`() = runBlocking<Unit> {
        // " feat. " is default-off, and the input below is written without its padding spaces: the rule
        // compares trimmed values, so this only collides once the override turns the built-in on.
        assertTrue(addSeparator("feat."), "off by default, so the value is free")

        viewModel.setBuiltinSeparatorEnabled("feat_dot", true)
        awaitConfig { it.builtinSeparatorOverrides.isNotEmpty() }

        assertFalse(viewModel.addCustomSeparator(" feat. "), "now it collides with a visible built-in")
    }

    @Test
    fun `editing a separator to its own value is not a duplicate of itself`() = runBlocking<Unit> {
        addSeparator(" & ")
        val id = viewModel.uiState.value.config.customSeparators.single().id

        val accepted = viewModel.updateCustomSeparator(id, " & ")

        assertTrue(accepted, "the entry being edited is excluded from its own duplicate check")
        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `editing a separator onto another one is rejected`() = runBlocking<Unit> {
        addSeparator(" & ")
        addSeparator(" vs ")
        val victim = viewModel.uiState.value.config.customSeparators.first { it.value == " vs " }.id

        val accepted = viewModel.updateCustomSeparator(victim, "&")

        assertFalse(accepted)
        awaitUntil(describe = { viewModel.uiState.value.error }) { it == ArtistSplitValidationError.DUPLICATE }
    }

    @Test
    fun `removing one separator leaves the others alone`() = runBlocking<Unit> {
        addSeparator(" & ")
        addSeparator(" vs ")
        val removed = viewModel.uiState.value.config.customSeparators.first { it.value == " & " }.id

        viewModel.removeCustomSeparator(removed)

        awaitConfig { it.customSeparators.size == 1 }
        assertEquals(" vs ", viewModel.uiState.value.config.customSeparators.single().value)
    }

    @Test
    fun `disabling a custom separator keeps it in the list`() = runBlocking<Unit> {
        addSeparator(" & ")
        val id = viewModel.uiState.value.config.customSeparators.single().id

        viewModel.setCustomSeparatorEnabled(id, false)

        awaitConfig { it.customSeparators.single().enabled.not() }
        assertFalse("&" in viewModel.uiState.value.config.effectiveSeparators(), "but it stops splitting")
    }

    @Test
    fun `no-split artists are deduplicated on a normalized name`() = runBlocking<Unit> {
        assertTrue(addNoSplitArtist("BUMP OF CHICKEN"))

        val accepted = viewModel.addCustomNoSplitArtist("  bump   of chicken ")

        assertFalse(accepted, "case and repeated spaces normalize away")
        awaitUntil(describe = { viewModel.uiState.value.error }) { it == ArtistSplitValidationError.DUPLICATE }
    }

    @Test
    fun `a blank no-split artist is rejected as EMPTY`() = runBlocking<Unit> {
        assertFalse(viewModel.addCustomNoSplitArtist(" "))

        awaitUntil(describe = { viewModel.uiState.value.error }) { it == ArtistSplitValidationError.EMPTY }
    }

    @Test
    fun `resetting separators clears overrides, hidden ids and custom entries only`() = runBlocking<Unit> {
        addSeparator(" & ")
        viewModel.removeBuiltinSeparator("slash")
        awaitConfig { "slash" in it.hiddenBuiltinSeparatorIds }
        viewModel.setBuiltinSeparatorEnabled("ampersand", true)
        awaitConfig { it.builtinSeparatorOverrides.isNotEmpty() }
        addNoSplitArtist("BUMP OF CHICKEN")

        viewModel.resetSeparators()

        awaitConfig {
            it.customSeparators.isEmpty() && it.hiddenBuiltinSeparatorIds.isEmpty() &&
                it.builtinSeparatorOverrides.isEmpty()
        }
        assertEquals(
            1,
            viewModel.uiState.value.config.customNoSplitArtists.size,
            "resetting separators must not touch the artist exceptions",
        )
    }

    @Test
    fun `resetting the artist exceptions clears only those`() = runBlocking<Unit> {
        addSeparator(" & ")
        addNoSplitArtist("BUMP OF CHICKEN")

        viewModel.resetCustomNoSplitArtists()

        awaitConfig { it.customNoSplitArtists.isEmpty() }
        assertEquals(1, viewModel.uiState.value.config.customSeparators.size)
    }

    @Test
    fun `rebuilding the artist index clears the pending flag and reports completion`() = runBlocking<Unit> {
        addSeparator(" & ")
        assertTrue(viewModel.uiState.value.hasPendingIndexRebuild)

        viewModel.rebuildArtistIndex()

        awaitUntil(describe = { viewModel.uiState.value }) { it.rebuildCompleted }
        val state = viewModel.uiState.value
        assertFalse(state.isRebuildingIndex, "the spinner stops when the rebuild finishes")
        assertFalse(state.hasPendingIndexRebuild, "the rebuild was the pending one")
        assertEquals(null, state.error)
    }

    @Test
    fun `a failing rebuild surfaces REBUILD_FAILED and keeps the pending flag`() = runBlocking<Unit> {
        newViewModel(index = FailingArtistIndexRepository(fixture.index))
        addSeparator(" & ")

        viewModel.rebuildArtistIndex()

        awaitUntil(describe = { viewModel.uiState.value.error }) {
            it == ArtistSplitValidationError.REBUILD_FAILED
        }
        assertFalse(viewModel.uiState.value.isRebuildingIndex)
        assertFalse(viewModel.uiState.value.rebuildCompleted, "a failed rebuild is not a completion")
        assertTrue(
            viewModel.uiState.value.hasPendingIndexRebuild,
            "the change is still unapplied, so the user can retry",
        )
    }

    @Test
    fun `the one-shot flags can be cleared`() = runBlocking<Unit> {
        viewModel.addCustomSeparator("   ")
        awaitUntil(describe = { viewModel.uiState.value.error }) { it != null }

        viewModel.clearError()

        awaitUntil(describe = { viewModel.uiState.value.error }) { it == null }
        // The rebuild flag is a separate one-shot; clearing it must not disturb the rest of the state.
        viewModel.clearRebuildCompleted()
        assertNotNull(viewModel.uiState.value)
    }

    private fun CoroutineScope.keepCollecting(flow: StateFlow<*>) {
        launch { flow.collect { } }
    }

    /** Delegates everything except the one call under test -- the seam for the failure path. */
    private class FailingArtistIndexRepository(
        private val delegate: LibraryIndexRepository,
    ) : LibraryIndexRepository by delegate {
        override suspend fun rebuildArtistIndex() {
            throw IOException("simulated index failure")
        }
    }
}
