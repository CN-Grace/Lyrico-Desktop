package com.lonx.lyrico.worker.processor

import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.model.lyrics.SongSearchResult
import com.lonx.lyrico.data.model.metadata.MetadataApplyPolicy
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.metadata.MetadataWriteMode
import com.lonx.lyrico.data.model.metadata.SearchResultApplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.Test

class MatchMetadataProcessorTest {
    @Test
    fun normalizedFieldsKeepsOnlyHostStandardFields() {
        val result = SongSearchResult(
            id = "song-1",
            pluginId = "com.example.source",
            pluginName = "Example",
            title = "Display Title",
            artist = "Display Artist",
            duration = 180000,
            fields = mapOf(
                "title" to "Field Title",
                "lyrics" to "lyric body",
                "song_mid" to "private-id"
            ),
            internal = mapOf("song_mid" to "private-id")
        )

        val normalized = result.normalizedFields()

        assertEquals(normalized["title"], "Field Title")
        assertEquals(normalized["lyrics"], "lyric body")
        assertEquals(normalized["artist"], "Display Artist")
        assertFalse(normalized.containsKey("song_mid"))
        assertFalse(normalized.containsKey("duration"))
    }

    @Test
    fun applierSupplementsCommentWhenCurrentCommentIsBlank() {
        val patch = SearchResultApplier.buildPatch(
            current = AudioTagData(comment = ""),
            fields = mapOf("comment" to "candidate comment"),
            policy = MetadataApplyPolicy(
                fieldModes = mapOf(MetadataFieldTarget.COMMENT to MetadataWriteMode.SUPPLEMENT)
            )
        )

        assertEquals(patch.comment, "candidate comment")
    }

    @Test
    fun applierDoesNotSupplementCommentWhenCurrentCommentExists() {
        val patch = SearchResultApplier.buildPatch(
            current = AudioTagData(comment = "existing comment"),
            fields = mapOf("comment" to "candidate comment"),
            policy = MetadataApplyPolicy(
                fieldModes = mapOf(MetadataFieldTarget.COMMENT to MetadataWriteMode.SUPPLEMENT)
            )
        )

        assertEquals(null, patch.comment)
    }

    @Test
    fun applierDoesNotPatchCoverWhenCoverTargetIsDisabled() {
        val patch = SearchResultApplier.buildPatch(
            current = AudioTagData(),
            fields = mapOf("cover_url" to "https://example.com/cover.jpg"),
            policy = MetadataApplyPolicy(
                fieldModes = mapOf(MetadataFieldTarget.COVER to MetadataWriteMode.DISABLED)
            )
        )

        assertEquals(null, patch.picUrl)
    }

    @Test
    fun applierSupplementsDefaultSelectedFieldWhenCurrentValueIsBlank() {
        val patch = SearchResultApplier.buildPatch(
            current = AudioTagData(title = ""),
            fields = mapOf("title" to "candidate title"),
            policy = MetadataApplyPolicy(
                fieldModes = mapOf(MetadataFieldTarget.TITLE to MetadataWriteMode.SUPPLEMENT)
            )
        )

        assertEquals(patch.title, "candidate title")
    }
}
