package com.lonx.lyrico.data.model.entity

import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class SongFieldBlankTest {

    private fun song(
        title: String? = null,
        artist: String? = null,
        discNumber: Int? = null,
        rating: Int? = null,
        lyrics: String? = null,
        replayGainTrackGain: String? = null,
    ) = SongEntity(
        folderId = 1L,
        mediaId = 1L,
        filePath = "/music/1.flac",
        fileName = "1.flac",
        title = title,
        artist = artist,
        discNumber = discNumber,
        rating = rating,
        lyrics = lyrics,
        replayGainTrackGain = replayGainTrackGain,
    )

    @Test
    fun `text fields are blank when null or whitespace only`() {
        assertTrue(song().isTargetBlank(MetadataFieldTarget.TITLE))
        assertTrue(song(title = "   ").isTargetBlank(MetadataFieldTarget.TITLE))
        assertFalse(song(title = "Song").isTargetBlank(MetadataFieldTarget.TITLE))
    }

    @Test
    fun `numeric fields are blank only when null`() {
        assertTrue(song().isTargetBlank(MetadataFieldTarget.DISC_NUMBER))
        assertFalse(song(discNumber = 0).isTargetBlank(MetadataFieldTarget.DISC_NUMBER))
        assertTrue(song().isTargetBlank(MetadataFieldTarget.RATING))
        assertFalse(song(rating = 0).isTargetBlank(MetadataFieldTarget.RATING))
    }

    @Test(expected = IllegalStateException::class)
    fun `cover requires a file lookup instead of guessing from database`() {
        song().isTargetBlank(MetadataFieldTarget.COVER)
    }

    @Test(expected = IllegalStateException::class)
    fun `custom tags require the tag index instead of guessing from database`() {
        song().isTargetBlank(MetadataFieldTarget.CUSTOM)
    }

    @Test
    fun `replay gain and lyrics use the same blank rules as values`() {
        assertTrue(song().isTargetBlank(MetadataFieldTarget.LYRICS))
        assertFalse(song(lyrics = "la").isTargetBlank(MetadataFieldTarget.LYRICS))
        assertTrue(song().isTargetBlank(MetadataFieldTarget.REPLAY_GAIN_TRACK_GAIN))
        assertFalse(
            song(replayGainTrackGain = "-6.00 dB")
                .isTargetBlank(MetadataFieldTarget.REPLAY_GAIN_TRACK_GAIN)
        )
    }

    @Test
    fun `artist uses the artist tag not album artist`() {
        assertTrue(song().isTargetBlank(MetadataFieldTarget.ARTIST))
        assertFalse(song(artist = "Band").isTargetBlank(MetadataFieldTarget.ARTIST))
    }
}
