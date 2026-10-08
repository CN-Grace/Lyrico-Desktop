package com.lonx.lyrico.data.model

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.chinese_conversion_mode_none
import com.lonx.lyrico.resources.chinese_conversion_mode_simplified_to_traditional
import com.lonx.lyrico.resources.chinese_conversion_mode_traditional_to_simplified
import org.jetbrains.compose.resources.StringResource


enum class ConversionMode(
    val labelRes: StringResource
) {
    NONE(Res.string.chinese_conversion_mode_none),
    TRADITIONAL_TO_SIMPLIFIED(Res.string.chinese_conversion_mode_traditional_to_simplified),
    SIMPLIFIED_TO_TRADITIONAL(Res.string.chinese_conversion_mode_simplified_to_traditional),
}