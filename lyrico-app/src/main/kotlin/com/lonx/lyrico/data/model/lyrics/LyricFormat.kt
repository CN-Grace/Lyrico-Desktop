package com.lonx.lyrico.data.model.lyrics

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.lyric_format_enhanced
import com.lonx.lyrico.resources.lyric_format_plain
import com.lonx.lyrico.resources.lyric_format_ttml
import com.lonx.lyrico.resources.lyric_format_verbatim
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

@Serializable
enum class LyricFormat(
    val labelRes: StringResource
) {
    PLAIN_LRC(Res.string.lyric_format_plain),
    VERBATIM_LRC(Res.string.lyric_format_verbatim),
    ENHANCED_LRC(Res.string.lyric_format_enhanced),
    TTML(Res.string.lyric_format_ttml),
}