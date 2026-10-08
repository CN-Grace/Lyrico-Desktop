package com.lonx.lyrico.data.model.lyrics

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.lyrics_convert
import com.lonx.lyrico.resources.lyrics_format_line_order
import com.lonx.lyrico.resources.lyrics_remove_tag_lines
import com.lonx.lyrico.resources.remove_empty_lines
import org.jetbrains.compose.resources.StringResource

@kotlinx.serialization.Serializable
enum class LyricsOperation(val titleRes: StringResource) {
    CONVERT(Res.string.lyrics_convert),
    SORT(Res.string.lyrics_format_line_order),
    REMOVE_EMPTY(Res.string.remove_empty_lines),
    REMOVE_TAGS(Res.string.lyrics_remove_tag_lines);

    fun options(format: LyricFormat? = null) = LyricsProcessingOptions(
        targetFormat = if (this == CONVERT) format else null,
        formatLineOrder = this == SORT,
        removeEmptyLines = this == REMOVE_EMPTY,
        removeTagLines = this == REMOVE_TAGS
    )
}
