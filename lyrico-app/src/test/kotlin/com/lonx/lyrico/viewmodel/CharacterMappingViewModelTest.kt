package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.model.CharacterMappingDefaults
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The character-mapping screen's state holder.
 *
 * It reads and writes through a real [SettingsRepositoryImpl] on a real DataStore file -- the rule
 * table it edits is the same one `SortKeyUpdater` consumes, so the interesting properties are the
 * merge semantics: a write replaces the whole `charMappings` map of *one* rule, so it must start from
 * the current viewmodel state, and a `null` (\"remove this character\") has to become an empty string
 * rather than a dropped key -- dropping the key would silently restore the built-in replacement.
 */
class CharacterMappingViewModelTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsRepository
    private lateinit var viewModel: CharacterMappingViewModel

    private val ruleId = CharacterMappingDefaults.DEFAULT_INVALID_CHARS.id

    @BeforeTest
    fun setUp() {
        // Real dispatcher rather than a test scheduler: the repository it talks to does real file IO.
        Dispatchers.setMain(Dispatchers.Default)
        settings = SettingsRepositoryImpl(
            createSettingsDataStore(Files.createTempFile("lyrico-char-mapping", ".preferences_pb"), scope)
        )
        viewModel = CharacterMappingViewModel(settings)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
    }

    @Test
    fun `the config is collected from settings on creation`() = runBlocking<Unit> {
        val config = awaitConfig { it != null }

        assertEquals(
            CharacterMappingDefaults.ALL_BUILTIN_RULES.map { it.id },
            config.rules.map { it.id },
            "the built-in rules are what an untouched install shows",
        )
        val rule = config.rules.single { it.id == ruleId }
        assertEquals("＼", rule.charMappings["\\"], "default replacement for a backslash")
        assertTrue(rule.isBuiltIn)
    }

    @Test
    fun `setting a replacement for an existing character replaces it`() = runBlocking<Unit> {
        awaitConfig { it != null }

        viewModel.updateCharacterMappingInRule(ruleId = ruleId, character = "\\", replacementChar = "#")

        val rule = awaitRule { it.charMappings["\\"] == "#" }
        assertEquals("#", rule.charMappings["\\"])
    }

    @Test
    fun `a null replacement becomes an empty string and keeps the key`() = runBlocking<Unit> {
        awaitConfig { it != null }

        viewModel.updateCharacterMappingInRule(ruleId = ruleId, character = "\\", replacementChar = null)

        val rule = awaitRule { it.charMappings["\\"] == "" }
        assertTrue("\\" in rule.charMappings, "the key must survive: dropping it would re-arm the default")
        assertEquals("", rule.charMappings["\\"], "null means 'delete this character', not 'keep it'")
    }

    @Test
    fun `adding a character leaves the rule's other mappings alone`() = runBlocking<Unit> {
        awaitConfig { it != null }

        viewModel.updateCharacterMappingInRule(ruleId = ruleId, character = "新", replacementChar = "Ｘ")

        val rule = awaitRule { it.charMappings["新"] == "Ｘ" }
        assertEquals("＼", rule.charMappings["\\"], "the previous mappings are the base of the write")
        assertEquals("：", rule.charMappings[":"])
        assertEquals(
            CharacterMappingDefaults.DEFAULT_INVALID_CHARS.charMappings.size + 1,
            rule.charMappings.size,
            "adding a character adds exactly one entry",
        )
    }

    @Test
    fun `a second edit builds on the first rather than on the defaults`() = runBlocking<Unit> {
        awaitConfig { it != null }

        viewModel.updateCharacterMappingInRule(ruleId = ruleId, character = "\\", replacementChar = "#")
        awaitRule { it.charMappings["\\"] == "#" }
        viewModel.updateCharacterMappingInRule(ruleId = ruleId, character = ":", replacementChar = "%")

        // The write replaces a rule's whole map, so it has to start from the current state: a write
        // based on the built-in defaults would silently undo the first edit.
        val rule = awaitRule { it.charMappings[":"] == "%" }
        assertEquals("#", rule.charMappings["\\"], "the earlier edit survives the later one")
        assertEquals("%", rule.charMappings[":"])
        assertEquals("＊", rule.charMappings["*"], "untouched entries are still the built-in ones")
    }

    @Test
    fun `editing one rule does not touch the others`() = runBlocking<Unit> {
        awaitConfig { it != null }

        viewModel.updateCharacterMappingInRule(ruleId = ruleId, character = "\\", replacementChar = "#")
        awaitRule { it.charMappings["\\"] == "#" }

        val config = awaitConfig { it != null }
        assertEquals(
            CharacterMappingDefaults.ALL_BUILTIN_RULES.size,
            config.rules.size,
            "the rule list is rewritten wholesale, so a wrong id match would drop or duplicate rules",
        )
    }

    @Test
    fun `an unknown rule id changes nothing`() = runBlocking<Unit> {
        awaitConfig { it != null }

        viewModel.updateCharacterMappingInRule(ruleId = "no_such_rule", character = "\\", replacementChar = "#")
        Thread.sleep(200L)

        val stored = settings.getCharacterMappingConfig()
        val rule = stored.rules.single { it.id == ruleId }
        assertEquals("＼", rule.charMappings["\\"], "an unmatched id must not write through")
    }

    @Test
    fun `an update before the first config arrives is ignored`() = runBlocking<Unit> {
        // A StandardTestDispatcher that is never advanced: the init block stays queued, so the
        // viewmodel genuinely has no config yet -- this is the state the `?: return@launch` guards.
        Dispatchers.setMain(StandardTestDispatcher())

        val fresh = CharacterMappingViewModel(settings)
        fresh.updateCharacterMappingInRule(ruleId = ruleId, character = "\\", replacementChar = "#")
        Thread.sleep(200L)

        val stored = settings.getCharacterMappingConfig()
        assertEquals("＼", stored.rules.single { it.id == ruleId }.charMappings["\\"])
    }

    private fun awaitConfig(
        predicate: (com.lonx.lyrico.data.model.CharacterMappingConfig) -> Boolean,
    ): com.lonx.lyrico.data.model.CharacterMappingConfig {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val value = viewModel.characterMappingConfig.value
            if (value != null && predicate(value)) return value
            Thread.sleep(10L)
        }
        // Report what actually happened rather than a bare timeout.
        val last = viewModel.characterMappingConfig.value
        assertNotNull(last, "the config was never collected from settings within 10s")
        assertTrue(predicate(last), "config stayed at $last")
        return last
    }

    private fun awaitRule(
        predicate: (com.lonx.lyrico.data.model.CharacterMappingRule) -> Boolean,
    ): com.lonx.lyrico.data.model.CharacterMappingRule =
        awaitConfig { config -> config.rules.any { it.id == ruleId && predicate(it) } }
            .rules.single { it.id == ruleId }
}
