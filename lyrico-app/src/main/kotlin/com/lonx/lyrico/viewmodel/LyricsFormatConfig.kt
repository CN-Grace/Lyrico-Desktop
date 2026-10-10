package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.model.lyrics.LyricFormat
import com.lonx.lyrico.data.model.lyrics.LyricsColumnEdit
import com.lonx.lyrico.data.model.lyrics.LyricsColumnMapping
import com.lonx.lyrico.data.model.lyrics.LyricsOperation
import com.lonx.lyrico.data.model.lyrics.LyricsProcessingOptions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The serialized configuration of a `CONVERT_LYRICS_FORMAT` batch task.
 *
 * On Android this class lives at the bottom of `BatchLyricsFormatViewModel.kt`. It is split out here
 * because it is not a viewmodel type: `LyricsFormatProcessor` and the batch runner's config summary
 * both deserialize it, so the engine would otherwise have to drag the whole launching viewmodel --
 * whose screens are not ported yet -- into the compiled tree. The package (`com.lonx.lyrico.viewmodel`)
 * is the one Android used, so the `configJson` written into the database stays byte-compatible and a
 * database carried over from Android keeps decoding.
 *
 * When `BatchLyricsFormatViewModel.kt` is ported, its own copy of this class is dropped and it
 * imports this one.
 */
@Serializable
data class LyricsFormatConfig(
    val targetFormat: LyricFormat? = null,
    val concurrency: Int,
    val formatLineOrder: Boolean = true,
    val removeTagLines: Boolean = false,
    val tagLineKeywords: List<String> = LyricsProcessingOptions.DefaultTagLineKeywords,
    val removeEmptyLines: Boolean = false,
    val twoColumnMapping: LyricsColumnMapping = LyricsColumnMapping.identity(2),
    val threeColumnMapping: LyricsColumnMapping = LyricsColumnMapping.identity(3),
    @SerialName("columnEdits")
    val legacyColumnEdits: Map<String, LyricsColumnEdit> = emptyMap(),
    val operation: LyricsOperation = LyricsOperation.CONVERT
)
