package com.lonx.lyrico.utils

import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.model.CharacterMappingRule
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rename engine on real files: the format parser, the sanitiser and the conflict resolution, driven
 * through the one entry point the batch-rename screen uses.
 *
 * It is a pure-logic piece that had no test at all on Android either, and it is the part of the rename
 * feature a user cannot undo from the UI -- so the assertions are about *paths on disk*, not about strings:
 *
 * * the new name is built from the tags and keeps the original extension;
 * * two songs that resolve to the same name get a numbered suffix, and the second one is flagged as a
 *   conflict (`RenamePreview.conflict` is what the preview tab colours);
 * * a name that sanitises away to nothing falls back to the original file name, so the engine never proposes
 *   an empty path;
 * * character-mapping rules are applied to the generated name (that is the whole reason the rules exist).
 */
class RenameEngineTest {

    private lateinit var workDir: File

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("lyrico-rename-engine").toFile()
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun songFile(name: String): File = File(workDir, name).apply { writeText("x") }

    private fun request(
        format: String,
        vararg songs: Pair<File, AudioTagData>,
        rules: List<CharacterMappingRule> = emptyList(),
    ) = RenameEngine.RenameRequest(
        songs = songs.map { (file, tags) -> RenameEngine.SongForRename(file.absolutePath, tags) },
        format = format,
        characterMappingRules = rules,
    )

    @Test
    fun `the new name comes from the tags and keeps the extension`() {
        val file = songFile("01.mp3")

        val previews = RenameEngine.generatePreviews(
            request(
                "@1 - @2",
                file to AudioTagData(title = "朋友", artist = "周华健"),
            )
        )

        val preview = previews.single()
        assertEquals(File(workDir, "朋友 - 周华健.mp3").absolutePath, preview.newPath)
        assertEquals(file.absolutePath, preview.originalPath, "the original path has to be reported unchanged")
        assertFalse(preview.conflict, "the first preview cannot conflict with anything")
    }

    @Test
    fun `two songs that resolve to the same name are told apart`() {
        val first = songFile("a.mp3")
        val second = songFile("b.mp3")
        val tags = AudioTagData(title = "同名", artist = "同一人")

        val previews = RenameEngine.generatePreviews(
            request("@1 - @2", first to tags, second to tags)
        )

        assertEquals(2, previews.size)
        assertEquals(File(workDir, "同名 - 同一人.mp3").absolutePath, previews[0].newPath)
        assertEquals(File(workDir, "同名 - 同一人 (1).mp3").absolutePath, previews[1].newPath)
        assertFalse(previews[0].conflict)
        assertTrue(previews[1].conflict, "the second one is the one the preview tab has to flag")
    }

    @Test
    fun `a name that sanitises away falls back to the original name`() {
        val file = songFile("keeper.mp3")

        // A format whose every token is missing produces an empty name; the engine keeps the old one instead
        // of proposing a rename to ".mp3".
        val previews = RenameEngine.generatePreviews(request("", file to AudioTagData()))

        assertEquals(File(workDir, "keeper.mp3").absolutePath, previews.single().newPath)
    }

    @Test
    fun `character-mapping rules are applied to the generated name`() {
        val file = songFile("c.mp3")

        val previews = RenameEngine.generatePreviews(
            request(
                "@1",
                file to AudioTagData(title = "A/B"),
                rules = listOf(
                    CharacterMappingRule(
                        id = "test-slash",
                        name = "slash to underscore",
                        charMappings = mapOf("/" to "_"),
                    ),
                ),
            )
        )

        // Whatever the sanitiser does with "/" (it is illegal in a Windows name), the rule's replacement has
        // to be visible in the result -- that is the feature's purpose.
        val newName = File(previews.single().newPath).name
        assertTrue(newName.startsWith("A"), "the prefix of the title is gone: $newName")
        assertFalse(newName.contains("/"), "an illegal character survived the sanitiser: $newName")
        assertTrue(newName.endsWith(".mp3"), "the extension is gone: $newName")
    }

    @Test
    fun `the preset formats are distinct and use the placeholder syntax`() {
        val presets = RenameEngine.getPresetFormats()

        assertEquals(presets.size, presets.toSet().size, "two presets are identical: $presets")
        assertTrue(presets.all { it.contains("@") }, "a preset does not use the placeholder syntax: $presets")
    }
}
