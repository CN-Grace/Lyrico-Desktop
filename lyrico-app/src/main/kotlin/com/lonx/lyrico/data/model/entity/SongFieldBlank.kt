package com.lonx.lyrico.data.model.entity

import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget

/**
 * 库内歌曲记录是否**没有**某个字段的值。
 *
 * 与 `SearchResultApplier` 里对 `AudioTagData` 的判空是同一套语义，区别只是数据来源：
 * 编辑页拿到的是音频标签，这里拿到的是数据库记录。两者独立维护，新增字段时都要补。
 *
 * 封面和自定义标签需要单独的数据源，不能用数据库列假定其存在。
 */
fun SongEntity.isTargetBlank(target: MetadataFieldTarget): Boolean = when (target) {
    MetadataFieldTarget.TITLE -> title.isNullOrBlank()
    MetadataFieldTarget.ARTIST -> artist.isNullOrBlank()
    MetadataFieldTarget.ALBUM -> album.isNullOrBlank()
    MetadataFieldTarget.ALBUM_ARTIST -> albumArtist.isNullOrBlank()
    MetadataFieldTarget.GENRE -> genre.isNullOrBlank()
    MetadataFieldTarget.DATE -> date.isNullOrBlank()
    MetadataFieldTarget.TRACK_NUMBER -> trackerNumber.isNullOrBlank()
    MetadataFieldTarget.DISC_NUMBER -> discNumber == null
    MetadataFieldTarget.COMPOSER -> composer.isNullOrBlank()
    MetadataFieldTarget.LYRICIST -> lyricist.isNullOrBlank()
    MetadataFieldTarget.COMMENT -> comment.isNullOrBlank()
    MetadataFieldTarget.LYRICS -> lyrics.isNullOrBlank()
    MetadataFieldTarget.LANGUAGE -> language.isNullOrBlank()
    MetadataFieldTarget.COPYRIGHT -> copyright.isNullOrBlank()
    MetadataFieldTarget.RATING -> rating == null
    MetadataFieldTarget.REPLAY_GAIN_TRACK_GAIN -> replayGainTrackGain.isNullOrBlank()
    MetadataFieldTarget.REPLAY_GAIN_TRACK_PEAK -> replayGainTrackPeak.isNullOrBlank()
    MetadataFieldTarget.REPLAY_GAIN_ALBUM_GAIN -> replayGainAlbumGain.isNullOrBlank()
    MetadataFieldTarget.REPLAY_GAIN_ALBUM_PEAK -> replayGainAlbumPeak.isNullOrBlank()
    MetadataFieldTarget.REPLAY_GAIN_REFERENCE_LOUDNESS -> replayGainReferenceLoudness.isNullOrBlank()
    // 封面与自定义标签不在数据库记录里，无法在这里判断。
    MetadataFieldTarget.COVER, MetadataFieldTarget.CUSTOM ->
        error("$target requires a dedicated lookup")
}
