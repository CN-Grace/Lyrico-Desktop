package com.lonx.lyrico.data.model.lyrics

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.lyric_line_original
import com.lonx.lyrico.resources.lyric_line_romanization
import com.lonx.lyrico.resources.lyric_line_translation
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

@Serializable
enum class LyricLineTrack(
    val labelRes: StringResource
) {
    ORIGINAL(Res.string.lyric_line_original),
    ROMANIZATION(Res.string.lyric_line_romanization),
    TRANSLATION(Res.string.lyric_line_translation);
}

val DefaultLyricLineOrder = listOf(
    LyricLineTrack.ORIGINAL,
    LyricLineTrack.ROMANIZATION,
    LyricLineTrack.TRANSLATION
)

fun List<LyricLineTrack>.normalizedLyricLineOrder(): List<LyricLineTrack> {
    return (this + DefaultLyricLineOrder).distinct()
        .filter { it in DefaultLyricLineOrder }
}

fun visibleLyricLineTracks(
    showRomanization: Boolean,
    showTranslation: Boolean,
    onlyTranslationIfAvailable: Boolean
): List<LyricLineTrack> {
    if (showTranslation && onlyTranslationIfAvailable) {
        return listOf(LyricLineTrack.TRANSLATION)
    }

    return buildList {
        add(LyricLineTrack.ORIGINAL)
        if (showRomanization) add(LyricLineTrack.ROMANIZATION)
        if (showTranslation) add(LyricLineTrack.TRANSLATION)
    }
}
