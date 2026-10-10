package com.lonx.lyrico.domain.song.usecase

import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.song.tag.AudioTagMutationFactory
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode

class OverwriteSongTagsUseCase(
    private val saveAudioTagsUseCase: SaveAudioTagsUseCase
) {
    /**
     * @param picturesAuthored 用户是否基于一份成功读取的图片列表改动过它。为 true 时即使图片
     *   被删空也会写入，否则可能把「删掉最后一张图片」当成「没读到图片」而丢掉这次修改。
     */
    suspend operator fun invoke(
        uri: String,
        tagData: AudioTagData,
        picturesAuthored: Boolean = false
    ): SaveAudioTagsResult {
        val mutation = AudioTagMutationFactory.fromAudioTagData(
            data = tagData,
            mode = AudioTagMutationMode.Overwrite,
            picturesAuthored = picturesAuthored
        )
        return saveAudioTagsUseCase(uri, mutation)
    }
}
