package com.lonx.lyrico.probe

import androidx.lifecycle.SavedStateHandle
import com.lonx.lyrico.data.model.lyrics.LyricsLine
import com.lonx.lyrico.data.model.lyrics.LyricsPayloadType
import com.lonx.lyrico.data.model.lyrics.LyricsResult
import com.lonx.lyrico.data.model.lyrics.LyricsWord
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.search.LyricsSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Probe: does the multiplatform `SavedStateHandle` accept a plain data class?
 *
 * The ported search screens hand their result back to the caller through
 * `ResultBackNavigator<T>.navigateBack(result: T)`. Android's implementation put the value into the
 * previous back-stack entry's `SavedStateHandle`, and Android only allowed `Parcelable`/primitives
 * there — which is exactly why `LyricsSearchResult` carried `@Parcelize`. On desktop there is no
 * `Bundle`, no parcel and no process death, so whether an arbitrary object is accepted is a property
 * of this navigation build, not of the Android one. It is measured here rather than guessed, because
 * the answer decides whether the port transports the object directly or has to serialize it (and a
 * silent field-drop would be far worse than an extra serializer).
 */
class SavedStateHandleProbeTest {

    @Test
    fun `plain data class round-trips through SavedStateHandle`() {
        val handle = SavedStateHandle()
        val value = lyricsResult()
        handle["result"] = value
        val readBack = handle.get<LyricsSearchResult>("result")
        assertEquals(value, readBack)
        assertSame(value, readBack, "the same instance should come back, not a copy")
    }

    @Test
    fun `nested collections and enums survive too`() {
        val handle = SavedStateHandle()
        val value = LyricsSearchResult(
            title = "山丘",
            artist = "李宗盛",
            album = null,
            lyrics = "[00:01.00]越过山丘",
            date = null,
            trackerNumber = null,
            picUrl = null,
            pluginId = "demo",
            pluginName = "Demo",
            applyTargets = setOf(MetadataFieldTarget.TITLE, MetadataFieldTarget.ARTIST),
            fields = mapOf("title" to "山丘", "custom" to "x")
        )
        handle["result"] = value
        val readBack = handle.get<LyricsSearchResult>("result")!!
        assertEquals(setOf(MetadataFieldTarget.TITLE, MetadataFieldTarget.ARTIST), readBack.applyTargets)
        assertEquals("山丘", readBack.fields["title"])
    }

    @Test
    fun `an absent key reads back as null rather than throwing`() {
        val handle = SavedStateHandle()
        assertEquals(null, handle.get<LyricsSearchResult>("result"))
        assertFalse(handle.contains("result"))
        handle["result"] = lyricsResult()
        assertTrue(handle.contains("result"))
    }

    /**
     * A `LyricsResult` travels inside [LyricsSearchResult] only as text, but it is the other type the
     * search screens hold in their state; pin that it, too, is storable, so a later caller that wants
     * to hand the whole parsed result back is not blocked by an unmeasured assumption.
     */
    @Test
    fun `LyricsResult is storable as well`() {
        val handle = SavedStateHandle()
        val parsed = LyricsResult(
            tags = emptyMap(),
            original = listOf(
                LyricsLine(
                    start = 0L,
                    end = 1000L,
                    words = listOf(
                        LyricsWord(start = 0L, end = 1000L, text = "越过山丘")
                    )
                )
            ),
            translated = null,
            romanization = null,
            payloadType = LyricsPayloadType.RAW_PLAIN_LRC,
            rawPlainLrc = "[00:01.00]越过山丘"
        )
        handle["lyrics"] = parsed
        assertEquals(parsed, handle.get<LyricsResult>("lyrics"))
    }

    private fun lyricsResult() = LyricsSearchResult(
        title = "t",
        artist = "a",
        album = "al",
        lyrics = "l",
        date = "d",
        trackerNumber = "1",
        picUrl = "http://example.invalid/cover.png",
        pluginId = "demo",
        pluginName = "Demo"
    )
}
