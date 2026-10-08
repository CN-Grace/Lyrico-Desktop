package com.lonx.lyrico.data.song.tag

import com.lonx.audiotag.model.AudioTagData

interface AudioTagRepository {
    suspend fun read(
        uri: String,
        options: AudioTagReadOptions = AudioTagReadOptions()
    ): AudioTagData

    suspend fun overwrite(
        uri: String,
        mutation: AudioTagMutation
    ): AudioTagWriteResult

    suspend fun patch(
        uri: String,
        mutation: AudioTagMutation
    ): AudioTagWriteResult
}
