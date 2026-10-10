package com.lonx.lyrico.utils

import com.lonx.lyrico.data.model.lyrics.LyricsLine
import com.lonx.lyrico.data.model.lyrics.LyricsResult
import com.lonx.lyrico.data.model.lyrics.LyricsWord
import com.lonx.lyrico.data.model.plugin.GlobalFieldProcessSettings
import com.lonx.lyrico.data.model.plugin.PluginFieldProcessConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginFieldPostProcessorTest {
    @Test
    fun structuredLyricsPreservePureSpaceWordsWhenTrimIsEnabled() {
        val processor = PluginFieldPostProcessor(
            GlobalFieldProcessSettings(
                trim = true,
                normalizeWhitespace = true,
                removeEmptyLines = true
            )
        )
        val lyrics = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                LyricsLine(
                    start = 0L,
                    end = 5000L,
                    words = listOf(
                        LyricsWord(start = 0L, end = 1674L, text = "About"),
                        LyricsWord(start = 1674L, end = 3348L, text = " "),
                        LyricsWord(start = 3348L, end = 5022L, text = "You")
                    )
                )
            ),
            translated = null,
            romanization = null
        )

        val processed = processor.processLyrics(
            lyrics = lyrics,
            config = PluginFieldProcessConfig(pluginId = "test")
        )

        assertEquals(listOf("About", " ", "You"), processed.original.first().words.map { it.text })
    }
}
