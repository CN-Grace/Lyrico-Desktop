package com.lonx.lyrico.utils

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Reads a string resource and substitutes [formatArgs] into it, the way Android's
 * `Context.getString(id, args)` does.
 *
 * `org.jetbrains.compose.resources.stringResource(resource, vararg formatArgs)` looks like it does
 * this, but on Compose Multiplatform 1.12 it does not go through [String.format]. Its runtime calls
 * `StringResourcesUtilsKt.replaceWithArgs`, which replaces only *positional* placeholders of the
 * literal form `%1$s` / `%1$d`:
 *
 * * a plain `%d` or `%s` is left in the text, so the UI would show "歌曲（%d）" instead of
 *   "歌曲（4）" - measured by OCR-ing a screenshot of the running window, see `PLAN.md`;
 * * flags, width and precision are not understood at all, so `%.2f` or `%1$.1f` also survive
 *   verbatim.
 *
 * Every Android string in this app uses plain placeholders (`%d`, `%s`, `%.2f`) because Android
 * handles both forms, so the library's formatter cannot be used here. Formatting explicitly keeps
 * the strings byte-identical to the Android originals and gives `%f`, widths and flags the same
 * meaning they have on Android.
 *
 * Do not call `stringResource(resource, args)` or `getString(resource, args)` directly in main
 * sources; `StringFormattingGuardTest` fails the build if that happens.
 *
 * The other half of "how the library reads a resource" (escapes and whitespace) is pinned by
 * `ComposeStringResourcesTest`: `\n` is unescaped, but the indentation of a multi-line body is kept
 * where Android's `aapt2` trims it.
 */
@Composable
fun formattedStringResource(resource: StringResource, vararg formatArgs: Any): String =
    stringResource(resource).format(*formatArgs)

/**
 * The same substitution for code that runs outside a composition, such as a coroutine resolving a
 * message for a snackbar. See [formattedStringResource] for why the library's own overload cannot be
 * used.
 */
suspend fun formattedString(resource: StringResource, vararg formatArgs: Any): String =
    getString(resource).format(*formatArgs)
