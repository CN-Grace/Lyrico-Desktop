package com.lonx.lyrico.data.model.metadata

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.extra_write_mode_disabled
import com.lonx.lyrico.resources.extra_write_mode_overwrite
import com.lonx.lyrico.resources.extra_write_mode_supplement
import com.lonx.lyrico.resources.label_album
import com.lonx.lyrico.resources.label_album_artist
import com.lonx.lyrico.resources.label_artists
import com.lonx.lyrico.resources.label_comment
import com.lonx.lyrico.resources.label_composer
import com.lonx.lyrico.resources.label_copyright
import com.lonx.lyrico.resources.label_cover
import com.lonx.lyrico.resources.label_custom
import com.lonx.lyrico.resources.label_date
import com.lonx.lyrico.resources.label_disc_number
import com.lonx.lyrico.resources.label_genre
import com.lonx.lyrico.resources.label_language
import com.lonx.lyrico.resources.label_lyricist
import com.lonx.lyrico.resources.label_lyrics
import com.lonx.lyrico.resources.label_rating
import com.lonx.lyrico.resources.label_replaygain_album_gain
import com.lonx.lyrico.resources.label_replaygain_album_peak
import com.lonx.lyrico.resources.label_replaygain_reference_loudness
import com.lonx.lyrico.resources.label_replaygain_track_gain
import com.lonx.lyrico.resources.label_replaygain_track_peak
import com.lonx.lyrico.resources.label_title
import com.lonx.lyrico.resources.label_track_number
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

@Serializable
enum class MetadataWriteMode(
    val labelRes: StringResource
) {
    DISABLED(Res.string.extra_write_mode_disabled),
    SUPPLEMENT(Res.string.extra_write_mode_supplement),
    OVERWRITE(Res.string.extra_write_mode_overwrite)
}

@Serializable
enum class MetadataFieldTarget(
    val labelRes: StringResource
)  {
    TITLE(Res.string.label_title),
    ARTIST(Res.string.label_artists),
    ALBUM(Res.string.label_album),
    ALBUM_ARTIST(Res.string.label_album_artist),
    GENRE(Res.string.label_genre),
    DATE(Res.string.label_date),
    TRACK_NUMBER(Res.string.label_track_number),
    DISC_NUMBER(Res.string.label_disc_number),
    COMPOSER(Res.string.label_composer),
    LYRICIST(Res.string.label_lyricist),
    COMMENT(Res.string.label_comment),
    LYRICS(Res.string.label_lyrics),
    COVER(Res.string.label_cover),
    LANGUAGE(Res.string.label_language),
    COPYRIGHT(Res.string.label_copyright),
    RATING(Res.string.label_rating),
    REPLAY_GAIN_TRACK_GAIN(Res.string.label_replaygain_track_gain),
    REPLAY_GAIN_TRACK_PEAK(Res.string.label_replaygain_track_peak),
    REPLAY_GAIN_ALBUM_GAIN(Res.string.label_replaygain_album_gain),
    REPLAY_GAIN_ALBUM_PEAK(Res.string.label_replaygain_album_peak),
    REPLAY_GAIN_REFERENCE_LOUDNESS(Res.string.label_replaygain_reference_loudness),
    CUSTOM(Res.string.label_custom)
}

data class MetadataApplyPolicy(
    val fieldModes: Map<MetadataFieldTarget, MetadataWriteMode>
) {
    fun modeOf(target: MetadataFieldTarget): MetadataWriteMode {
        return fieldModes[target] ?: MetadataWriteMode.DISABLED
    }

    companion object {
        fun overwriteAvailableFields(fields: Map<String, String>): MetadataApplyPolicy {
            return MetadataApplyPolicy(
                fields.keys
                    .mapNotNull { key -> StandardPluginField.fromKey(key)?.target }
                    .distinct()
                    .associateWith { MetadataWriteMode.OVERWRITE }
            )
        }
    }
}

enum class StandardPluginField(
    val key: String,
    val target: MetadataFieldTarget
) {
    TITLE("title", MetadataFieldTarget.TITLE),
    ARTIST("artist", MetadataFieldTarget.ARTIST),
    ALBUM("album", MetadataFieldTarget.ALBUM),
    ALBUM_ARTIST("album_artist", MetadataFieldTarget.ALBUM_ARTIST),
    GENRE("genre", MetadataFieldTarget.GENRE),
    DATE("date", MetadataFieldTarget.DATE),
    TRACK_NUMBER("track_number", MetadataFieldTarget.TRACK_NUMBER),
    DISC_NUMBER("disc_number", MetadataFieldTarget.DISC_NUMBER),
    COMPOSER("composer", MetadataFieldTarget.COMPOSER),
    LYRICIST("lyricist", MetadataFieldTarget.LYRICIST),
    COMMENT("comment", MetadataFieldTarget.COMMENT),
    LYRICS("lyrics", MetadataFieldTarget.LYRICS),
    COVER_URL("cover_url", MetadataFieldTarget.COVER),
    LANGUAGE("language", MetadataFieldTarget.LANGUAGE),
    COPYRIGHT("copyright", MetadataFieldTarget.COPYRIGHT),
    RATING("rating", MetadataFieldTarget.RATING),
    REPLAY_GAIN_TRACK_GAIN("replaygain_track_gain", MetadataFieldTarget.REPLAY_GAIN_TRACK_GAIN),
    REPLAY_GAIN_TRACK_PEAK("replaygain_track_peak", MetadataFieldTarget.REPLAY_GAIN_TRACK_PEAK),
    REPLAY_GAIN_ALBUM_GAIN("replaygain_album_gain", MetadataFieldTarget.REPLAY_GAIN_ALBUM_GAIN),
    REPLAY_GAIN_ALBUM_PEAK("replaygain_album_peak", MetadataFieldTarget.REPLAY_GAIN_ALBUM_PEAK),
    REPLAY_GAIN_REFERENCE_LOUDNESS(
        "replaygain_reference_loudness",
        MetadataFieldTarget.REPLAY_GAIN_REFERENCE_LOUDNESS
    );

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun fromKey(key: String): StandardPluginField? {
            return byKey[key]
        }
    }
}
