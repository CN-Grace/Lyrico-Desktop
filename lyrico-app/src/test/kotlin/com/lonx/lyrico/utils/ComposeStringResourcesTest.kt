package com.lonx.lyrico.utils

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.batch_match_stat_format
import com.lonx.lyrico.resources.dialog_delete_file_content
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compose Multiplatform does not read a string resource quite the way the Android resource pipeline
 * does. Two measured differences, both invisible to a test that only compares a call with itself:
 *
 * * `\n` in a body **is** unescaped, matching Android - `删除“%s”？\n此操作不可撤销` is one dialog
 *   line with a real line break.
 * * Whitespace around the text **is not** stripped, where Android's `aapt2` strips it. A body written
 *
 *   ```xml
 *   <string name="x">
 *       Success: %1$d
 *   </string>
 *   ```
 *
 *   therefore renders with a leading line break and four spaces on desktop. Android's rule, visible
 *   in this app's own resources, is narrower than "strip all whitespace": `<string
 *   name="batch_task_type_label">Task Type: </string>` keeps its trailing space, and the Android
 *   screen that renders it does `stringResource(R.string.batch_task_type_label) + typeLabel`, so a
 *   blanket strip would have shown "Task Type:Scan". So the artifact is only ever the whitespace that
 *   comes with a line break, and that is what this test bans: the XML body is not behavior, the
 *   rendered text is.
 *
 * The two entries that shipped padded with a line break (`batch_match_stat_format`,
 * `batch_match_duration_format`, from the resource migration) are written on one line now; the rest of
 * the rule is enforced by [isPaddedByLineBreak] and its sample cases.
 */
class ComposeStringResourcesTest {

    @Test
    fun `no string body is padded with a line break`() {
        val offenders = mutableListOf<String>()
        resourceFiles().forEach { file ->
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .filter { isPaddedByLineBreak(it.groupValues[2]) }
                .forEach { offenders += "${file.name}: ${it.groupValues[1]}" }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Put it on one line, or drop the line break if it was only for readability - Compose " +
                    "Multiplatform keeps the indentation that aapt2 strips. A trailing space on a " +
                    "single line (\"Task Type: \") is intentional and allowed:\n" +
                    offenders.joinToString("\n"),
            )
        }
    }

    @Test
    fun `the line break rule takes the real bodies and leaves the real exceptions`() {
        assertTrue(isPaddedByLineBreak("\n    Success: %1\$d\n    "), "a multi-line body is the artifact")
        assertTrue(isPaddedByLineBreak("Title\n    "), "a trailing line break is the artifact")

        assertFalse(isPaddedByLineBreak("Task Type: "), "a single-line trailing space is intentional")
        assertFalse(isPaddedByLineBreak("Task: "), "and so is a single-line leading one")
        assertFalse(isPaddedByLineBreak("Title：\n\n\\n• 只更新…"), "inner line breaks are content")
    }

    @Test
    fun `a rewritten multi-line body reads back without xml indentation`() = runBlocking<Unit> {
        val raw = getString(Res.string.batch_match_stat_format)
        assertEquals(raw.trim(), raw, "the body must not carry leading or trailing whitespace")
        assertFalse(raw.contains('\n'), "the body must be a single line, was [$raw]")
    }

    @Test
    fun `the backslash-n escape is unescaped`() = runBlocking<Unit> {
        val raw = getString(Res.string.dialog_delete_file_content)
        assertTrue(raw.contains('\n'), "the escape must become a real line break, was [$raw]")
        assertFalse(raw.contains("\\n"), "the escape must not survive into the rendered text, was [$raw]")
    }

    /**
     * True when the whitespace that [body] starts or ends with contains a line break - the shape that
     * only exists because the XML was written for readability, and the one aapt2 removes.
     */
    private fun isPaddedByLineBreak(body: String): Boolean =
        body.takeWhile { it.isWhitespace() }.contains('\n') ||
            body.takeLastWhile { it.isWhitespace() }.contains('\n')

    /** Compose resources, found by walking up from the working directory (repo root or module). */
    private fun resourceFiles(): List<File> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "src/main/composeResources")
            if (candidate.isDirectory) {
                return candidate.walkTopDown().filter { it.isFile && it.name == "strings.xml" }.sorted().toList()
            }
            val nested = File(dir, "lyrico-app/src/main/composeResources")
            if (nested.isDirectory) {
                return nested.walkTopDown().filter { it.isFile && it.name == "strings.xml" }.sorted().toList()
            }
            dir = dir.parentFile
        }
        fail("Could not locate src/main/composeResources from ${System.getProperty("user.dir")}")
    }
}
