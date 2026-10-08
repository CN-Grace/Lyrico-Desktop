package com.lonx.lyrico.data.model

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.theme_mode_auto
import com.lonx.lyrico.resources.theme_mode_dark
import com.lonx.lyrico.resources.theme_mode_light
import org.jetbrains.compose.resources.StringResource

enum class ThemeMode(
    val labelRes: StringResource
) {
    AUTO(Res.string.theme_mode_auto),
    LIGHT(Res.string.theme_mode_light),
    DARK(Res.string.theme_mode_dark)

}
