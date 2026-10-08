package com.lonx.lyrico.data.song.tag

import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.audiotag.model.AudioTagData
import com.lonx.audiotag.model.CustomTagField
import java.nio.file.InvalidPathException
import java.nio.file.Path

object AudioTagMutationFactory {
    /**
     * @param picturesAuthored 调用方是否已经拿到过完整的图片列表（读取成功）并且用户改过它。
     *   没有它就无法区分「用户把图片删光了」和「这次读取没拿到图片」——前者必须写成空列表，
     *   后者绝不能写，否则会把文件里原有的图片抹掉。
     */
    fun fromAudioTagData(
        data: AudioTagData,
        mode: AudioTagMutationMode,
        picturesAuthored: Boolean = false
    ): AudioTagMutation {
        val patchMode = mode == AudioTagMutationMode.Patch
        val fields = buildMap {
            putString(AudioTagFieldKey.Title, data.title, patchMode)
            putString(AudioTagFieldKey.Artist, data.artist, patchMode)
            putString(AudioTagFieldKey.Album, data.album, patchMode)
            putString(AudioTagFieldKey.AlbumArtist, data.albumArtist, patchMode)
            putString(AudioTagFieldKey.Genre, data.genre, patchMode)
            putString(AudioTagFieldKey.Date, data.date, patchMode)
            putString(AudioTagFieldKey.Language, data.language, patchMode)
            putString(AudioTagFieldKey.TrackNumber, data.trackNumber, patchMode)
            putInt(AudioTagFieldKey.DiscNumber, data.discNumber, patchMode)
            putString(AudioTagFieldKey.Composer, data.composer, patchMode)
            putString(AudioTagFieldKey.Lyricist, data.lyricist, patchMode)
            putString(AudioTagFieldKey.Comment, data.comment, patchMode)
            putString(AudioTagFieldKey.Lyrics, data.lyrics, patchMode)
            putString(AudioTagFieldKey.Copyright, data.copyright, patchMode)
            putInt(AudioTagFieldKey.Rating, data.rating, patchMode)
            putString(AudioTagFieldKey.ReplayGainTrackGain, data.replayGainTrackGain, patchMode)
            putString(AudioTagFieldKey.ReplayGainTrackPeak, data.replayGainTrackPeak, patchMode)
            putString(AudioTagFieldKey.ReplayGainAlbumGain, data.replayGainAlbumGain, patchMode)
            putString(AudioTagFieldKey.ReplayGainAlbumPeak, data.replayGainAlbumPeak, patchMode)
            putString(
                AudioTagFieldKey.ReplayGainReferenceLoudness,
                data.replayGainReferenceLoudness,
                patchMode
            )
        }

        val customFields = when {
            data.customFields.isNotEmpty() -> listOf(CustomTagFieldMutation.ReplaceAll(data.customFields))
            mode == AudioTagMutationMode.Overwrite -> listOf(CustomTagFieldMutation.ReplaceAll(emptyList()))
            else -> emptyList()
        }

        return AudioTagMutation(
            mode = mode,
            fields = fields,
            customFields = customFields,
            pictureUpdate = data.toPictureUpdate(mode, picturesAuthored)
        )
    }

    private fun MutableMap<AudioTagFieldKey, FieldMutation>.putString(
        key: AudioTagFieldKey,
        value: String?,
        patchMode: Boolean
    ) {
        when {
            value != null -> put(key, FieldMutation.Set(value))
            !patchMode -> put(key, FieldMutation.Clear)
        }
    }

    private fun MutableMap<AudioTagFieldKey, FieldMutation>.putInt(
        key: AudioTagFieldKey,
        value: Int?,
        patchMode: Boolean
    ) {
        when {
            value != null -> put(key, FieldMutation.Set(value.toString()))
            !patchMode -> put(key, FieldMutation.Clear)
        }
    }

    private fun AudioTagData.toPictureUpdate(
        mode: AudioTagMutationMode,
        picturesAuthored: Boolean
    ): PictureUpdate {
        val picUrl = picUrl
        if (picUrl != null) {
            val normalizedPicUrl = picUrl.trim()
            val basePictures = pictures.asOverwriteBase(mode, picturesAuthored)
            return if (normalizedPicUrl.isEmpty()) {
                PictureUpdate.RemovePicture(
                    type = AudioPictureType.FrontCover,
                    basePictures = basePictures
                )
            } else if (normalizedPicUrl.startsWith("http")) {
                PictureUpdate.ReplacePicture(
                    type = AudioPictureType.FrontCover,
                    source = PictureSource.UrlSource(normalizedPicUrl),
                    basePictures = basePictures
                )
            } else {
                // A cover picked from disk: the value is an absolute path. A stored value that is not
                // a usable path (a leftover `content://` row, say) leaves the picture untouched
                // rather than failing the whole tag write.
                val picturePath = normalizedPicUrl.toPathOrNull()
                if (picturePath == null) {
                    PictureUpdate.Unchanged
                } else {
                    PictureUpdate.ReplacePicture(
                        type = AudioPictureType.FrontCover,
                        source = PictureSource.FileSource(picturePath),
                        basePictures = basePictures
                    )
                }
            }
        }

        // 用户的改动以这份列表为准：即使被删空也要写下去（picturesAuthored 为 true）
        return if (mode == AudioTagMutationMode.Overwrite && (picturesAuthored || pictures.isNotEmpty())) {
            PictureUpdate.ReplaceAll(pictures)
        } else {
            PictureUpdate.Unchanged
        }
    }

    /**
     * 换封面时用来承载其余图片的基准列表。
     *
     * 用户改过图（[picturesAuthored]）时，**空列表也是有效基准**：否则下游会退回磁盘上的旧
     * 图片，「先把艺术家海报删光、再换封面」这种操作就会把删掉的海报又找回来。
     * 没改过图时保持 `null`，让下游沿用磁盘内容，避免读取失败时误删图片。
     */
    private fun List<AudioPicture>.asOverwriteBase(
        mode: AudioTagMutationMode,
        picturesAuthored: Boolean
    ): List<AudioPicture>? {
        return takeIf {
            mode == AudioTagMutationMode.Overwrite && (picturesAuthored || it.isNotEmpty())
        }
    }

    private fun String.toPathOrNull(): Path? = try {
        Path.of(this)
    } catch (_: InvalidPathException) {
        null
    }
}
