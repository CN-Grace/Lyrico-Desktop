package com.lonx.lyrico.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fails the build if a main source formats a Compose string resource through the library.
 *
 * `stringResource(resource, vararg formatArgs)` and `getString(resource, vararg formatArgs)` look like
 * the Android API but on Compose Multiplatform 1.12 only substitute positional `%1$s` / `%1$d`
 * placeholders (see [formattedStringResource] for the measurement). Every Android string in this app
 * uses plain `%d` / `%s` / `%.2f`, so a call through the library renders the placeholder itself - a
 * bug that a screenshot-free test suite cannot see, because a test that builds its expectation with
 * the same broken call agrees with the broken UI.
 *
 * The scan is deliberately textual, because the two forms compile to the same call: it strips
 * comments and string literals, then looks at each call that passes at least one argument and whose
 * first argument is a string resource - either the generated `Res.string...` property, or a value
 * declared as `StringResource` in the same file. Anything else, such as this app's own
 * `SourceRuntimeConfig.getString(key, default)`, is left alone.
 */
class StringFormattingGuardTest {

    @Test
    fun `no main source formats a string resource through the library`() {
        val offenders = mutableListOf<String>()
        sourceFiles().forEach { file ->
            val code = withoutCommentsAndLiterals(file.readText())
            val resources = stringResourceValuedNames(code)
            bannedCalls(code, "stringResource", resources).forEach { offenders += "${file.name}:$it" }
            bannedCalls(code, "getString", resources).forEach { offenders += "${file.name}:$it" }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Use formattedStringResource(Res.string.x, args), or formattedString(res, args) outside " +
                    "a composition - the library's own vararg formatting does not substitute plain " +
                    "%d/%s placeholders:\n" + offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `the guard finds the real thing and ignores everything else`() {
        val banned = withoutCommentsAndLiterals(BAD_SAMPLE)
        val names = stringResourceValuedNames(banned)
        assertTrue(bannedCalls(banned, "stringResource", names).isNotEmpty(), "a generated resource call must be reported")
        assertTrue(bannedCalls(banned, "getString", names).isNotEmpty(), "a StringResource-valued call must be reported")

        val fine = withoutCommentsAndLiterals(GOOD_SAMPLE)
        val fineNames = stringResourceValuedNames(fine)
        assertTrue(bannedCalls(fine, "stringResource", fineNames).isEmpty(), "a KDoc mention must not be reported")
        assertTrue(bannedCalls(fine, "getString", fineNames).isEmpty(), "the project helper must not be reported")
        assertTrue(bannedCalls(fine, "getString", fineNames).isEmpty(), "an unrelated getString must not be reported")
    }

    /** Main sources, found by walking up from the working directory (repo root or module). */
    private fun sourceFiles(): List<File> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "src/main/kotlin")
            if (candidate.isDirectory) {
                return candidate.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            val nested = File(dir, "lyrico-app/src/main/kotlin")
            if (nested.isDirectory) {
                return nested.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            dir = dir.parentFile
        }
        fail("Could not locate src/main/kotlin from ${System.getProperty("user.dir")}")
    }

    /** Blanks out comments and string literals, so only real code is scanned. */
    private fun withoutCommentsAndLiterals(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') {
                    out.append(' ')
                    i++
                }

                source.startsWith("/*", i) -> {
                    while (i < source.length && !source.startsWith("*/", i)) {
                        out.append(' ')
                        i++
                    }
                    repeat(2) {
                        if (i < source.length) {
                            out.append(' ')
                            i++
                        }
                    }
                }

                source.startsWith("\"\"\"", i) -> {
                    while (i < source.length && !source.startsWith("\"\"\"", i)) {
                        out.append(' ')
                        i++
                    }
                    repeat(3) {
                        if (i < source.length) {
                            out.append(' ')
                            i++
                        }
                    }
                }

                source[i] == '"' -> {
                    out.append(' ')
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') out.append(' ').also { i++ }
                        if (i < source.length) out.append(' ').also { i++ }
                    }
                    if (i < source.length) out.append(' ').also { i++ }
                }

                else -> out.append(source[i]).also { i++ }
            }
        }
        return out.toString()
    }

    /** Names declared as `StringResource` in this file, whose calls must go through the helper. */
    private fun stringResourceValuedNames(code: String): Set<String> =
        Regex("""\b(\w+)\s*:\s*StringResource\b""").findAll(code).map { it.groupValues[1] }.toSet()

    /** `line: description` for each `name(...)` call that passes arguments for a string resource. */
    private fun bannedCalls(code: String, name: String, resources: Set<String>): List<String> {
        val needle = "$name("
        val hits = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = code.indexOf(needle, from)
            if (start < 0) return hits
            from = start + needle.length
            // `formattedStringResource(` also ends with the needle; reject a preceding identifier char.
            if (start > 0 && (code[start - 1].isLetterOrDigit() || code[start - 1] == '_')) continue

            var depth = 1
            var i = from
            var topLevelCommas = 0
            var firstArgument = ""
            while (i < code.length && depth > 0) {
                val c = code[i]
                when {
                    c == '(' -> depth++
                    c == ')' -> depth--
                    c == ',' && depth == 1 -> {
                        topLevelCommas++
                        if (firstArgument.isEmpty()) firstArgument = code.substring(from, i).trim()
                    }
                }
                i++
            }
            if (topLevelCommas == 0) continue
            if (firstArgument.isEmpty()) firstArgument = code.substring(from, i - 1).trim()
            val isResource = firstArgument.startsWith("Res.string.") || firstArgument in resources
            if (isResource) hits += "${code.take(start).count { it == '\n' } + 1}: $name(firstArgument)"
        }
    }

    private companion object {
        const val BAD_SAMPLE = """
            package sample
            class Sample {
                fun render(res: StringResource, songs: List<Int>) {
                    val title = stringResource(Res.string.song_list_title, songs.size)
                    val other = getString(res, 1)
                }
            }
        """

        const val GOOD_SAMPLE = """
            package sample
            /** Do not call stringResource(resource, args) or getString(resource, args) directly. */
            class Sample {
                fun render(songs: List<Int>) {
                    val title = formattedStringResource(Res.string.song_list_title, songs.size)
                    // stringResource(Res.string.song_list_title, songs.size) would be wrong here
                }
            }
            @Serializable
            data class SourceRuntimeConfig(val values: Map<String, String> = emptyMap()) {
                fun getString(key: String, defaultValue: String = ""): String = values[key] ?: defaultValue
            }
        """
    }
}
