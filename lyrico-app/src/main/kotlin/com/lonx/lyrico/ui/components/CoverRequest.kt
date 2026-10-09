package com.lonx.lyrico.ui.components

import com.lonx.audiotag.model.AudioPictureType

/**
 * A file that may hold cover artwork, with the timestamp that invalidates its cached decode.
 *
 * Android addressed these files by `content://` URI. Desktop sound files are plain paths, so the
 * field carries the absolute Windows path instead; it is still called `uri` because the schema's
 * identity column is `songs.uri` and every call site already spells it that way.
 */
data class CoverCandidate(
    val uri: String,
    val lastUpdate: Long
)

data class CoverRequest(
    val uri: String,
    val lastUpdate: Long,
    val pictureType: AudioPictureType = AudioPictureType.FrontCover,
    val fallbackPictureTypes: List<AudioPictureType> = emptyList(),
    val fallbackToAny: Boolean = pictureType == AudioPictureType.FrontCover,
    val candidates: List<CoverCandidate> = emptyList(),
    val artistName: String? = null,
    val artistPosterFolders: List<String> = emptyList(),
    val artistPosterRevision: Long = 0L,
    /**
     * 只查外置海报文件夹，跳过标签里的内嵌图片。
     *
     * 单曲编辑页需要它：编辑页自己按归属规则（[com.lonx.lyrico.domain.poster.ArtistPosterGrouping]）
     * 把每张内嵌海报分给了某位艺术家，而 `TagLib.getPicture` 的描述回退是另一套规则（描述为空
     * 就当作通用图）。两边对同一张「没有描述」的图片会给出不同答案——多艺术家时编辑页认为它归属
     * 不明，取图链路却把它当成任何一位艺术家的海报，于是几位艺术家显示同一张图、各自的海报文件
     * 反而永远查不到。编辑页只用这个请求查文件，归属判断只保留一处。
     */
    val skipEmbeddedPictures: Boolean = false
) {
    companion object {
        /**
         * 创建缓存键：文件路径 + 图片类型 + 时间戳
         * 当文件修改时间改变时，缓存键也会改变，从而自动清除旧缓存
         */
        fun getCacheKey(
            path: String,
            timestamp: Long,
            pictureType: AudioPictureType,
            fallbackPictureTypes: List<AudioPictureType>,
            fallbackToAny: Boolean,
            candidates: List<CoverCandidate> = emptyList(),
            skipEmbeddedPictures: Boolean = false
        ): String {
            val fallbackKey = fallbackPictureTypes.joinToString(",") { it.tagLibName }
            val candidateKey = candidates.joinToString("|") {
                "${it.uri}@${it.lastUpdate}"
            }
            return "$path@${pictureType.tagLibName}@$fallbackKey@$fallbackToAny@$timestamp@$candidateKey" +
                "@embedded:${!skipEmbeddedPictures}"
        }
    }
}
