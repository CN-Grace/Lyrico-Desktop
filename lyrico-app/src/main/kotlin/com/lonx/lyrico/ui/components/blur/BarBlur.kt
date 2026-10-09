package com.lonx.lyrico.ui.components.blur

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.ui.components.scaffoldTopAppBarInsetsPadding
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

// A host can share its preference subscription with all child pages.
internal val LocalBarBlurEnabled = staticCompositionLocalOf<Boolean?> { null }

private const val BarBlurRadius = 32f
private const val BarSurfaceAlpha = 0.68f

/** Standard bars follow the application preference; floating effects opt in separately. */
@Composable
internal fun rememberBarBlurBackdrop(
    enabled: Boolean = rememberBarBlurEnabled(),
): LayerBackdrop? = rememberBlurBackdrop(enableBlur = enabled)

/** Apply to the scrolling content, before content insets, never to the bar itself. */
internal fun Modifier.blurSource(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) layerBackdrop(backdrop) else this

/** Owns the surface and safe-area padding. Child app bars must be transparent and omit insets. */
@Composable
internal fun BlurredTopBar(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BlurredBar(
        backdrop = backdrop,
        modifier = modifier.scaffoldTopAppBarInsetsPadding(),
        content = content,
    )
}

@Composable
internal fun rememberBlurBackdrop(enableBlur: Boolean = true): LayerBackdrop? {
    val surfaceColor = MiuixTheme.colorScheme.surface
    // Keep the backdrop stable when the preference changes.
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    return if (enableBlur && isRenderEffectSupported()) backdrop else null
}

/** 顶栏/底栏模糊开关，各页面统一从这里读。 */
@Composable
internal fun rememberBarBlurEnabled(): Boolean {
    LocalBarBlurEnabled.current?.let { return it }
    val settings: SettingsRepository = koinInject()
    val enabled by settings.barBlurEnabled.collectAsStateWithLifecycle(initialValue = false)
    return enabled
}

@Composable
internal fun BlurredBar(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    Box(
        // Paint behind the supplied padding too, including the top bar's safe area.
        modifier = (if (backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = BarBlurRadius,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(surfaceColor.copy(alpha = BarSurfaceAlpha)),
                    ),
                ),
            )
        } else {
            Modifier.background(surfaceColor)
        }).then(modifier),
    ) {
        content()
    }
}
