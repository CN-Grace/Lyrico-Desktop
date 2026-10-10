package com.lonx.lyrico.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Renders plugin-provided help text as Markdown, in the app's own Miuix theme.
 *
 * The Android app used `com.github.jeziellago:compose-markdown`'s `MarkdownText`, which is Android
 * only; the desktop port uses the multiplatform markdown renderer instead. The swap is not
 * mechanical, because the two libraries disagree about where theming comes from: `MarkdownText` took
 * a `style` and a `linkColor` and inherited everything else from the surrounding composition, whereas
 * the multiplatform renderer takes an explicit [MarkdownColors] + [MarkdownTypography] pair and its
 * own `markdownColor()`/`markdownTypography()` defaults read **Material 3**'s theme -- which this app
 * does not use (it is a Miuix app; see `LyricoDesktopApp.kt`). Using those defaults would paint
 * `LocalContentColor`-derived text over a Miuix surface, i.e. the wrong colour in at least one of the
 * two themes.
 *
 * So this file is the bridge: the two functions below build the library's value objects out of
 * [MiuixTheme], one Markdown colour and one Markdown text style per thing the renderer can draw.
 * Nothing here is a stylistic invention -- each mapping takes the Miuix token that plays the role the
 * Markdown token names:
 *
 * | Markdown | Miuix |
 * | --- | --- |
 * | body text, paragraphs, list items | `textStyles.body2` |
 * | headings 1-4 | `textStyles.title1`-`title4` |
 * | headings 5-6 | `textStyles.subtitle` |
 * | code, inline code | `body2` in `FontFamily.Monospace` |
 * | links | `body2` in `colorScheme.primary` |
 * | quote | `body2` in `colorScheme.onSurfaceVariantSummary` |
 * | text / code / divider colours | `onSurface` / `onSurfaceContainer` / `dividerLine` |
 *
 * Two known gaps, stated rather than hidden:
 *
 * * **Images render as nothing.** The renderer's default `ImageTransformer` is a no-op, and wiring it
 *   to Coil (as `compose-markdown` did) is not needed by any plugin manifest field the port has a
 *   fixture for. If a plugin ships an image in a `markdown` field it will be invisible.
 * * **Links are styled but not clickable.** Clicking behaviour lives in the renderer's annotator
 *   hooks; the Android renderer did not open links either (it had no handler at this call site), so
 *   this is parity, not a regression.
 */
@Composable
fun MiuixMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    Markdown(
        content = markdown,
        colors = miuixMarkdownColors(),
        typography = miuixMarkdownTypography(),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun miuixMarkdownColors(): MarkdownColors = DefaultMarkdownColors(
    text = MiuixTheme.colorScheme.onSurface,
    codeText = MiuixTheme.colorScheme.onSurfaceContainer,
    inlineCodeText = MiuixTheme.colorScheme.onSurfaceContainer,
    linkText = MiuixTheme.colorScheme.primary,
    codeBackground = MiuixTheme.colorScheme.surfaceContainer,
    inlineCodeBackground = MiuixTheme.colorScheme.surfaceContainer,
    dividerColor = MiuixTheme.colorScheme.dividerLine,
)

@Composable
private fun miuixMarkdownTypography(): MarkdownTypography {
    val styles = MiuixTheme.textStyles
    val body = styles.body2
    val code = body.copy(fontFamily = FontFamily.Monospace)
    return DefaultMarkdownTypography(
        h1 = styles.title1,
        h2 = styles.title2,
        h3 = styles.title3,
        h4 = styles.title4,
        h5 = styles.subtitle,
        h6 = styles.subtitle,
        text = body,
        code = code,
        inlineCode = code,
        quote = body.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        link = body.copy(color = MiuixTheme.colorScheme.primary),
    )
}
