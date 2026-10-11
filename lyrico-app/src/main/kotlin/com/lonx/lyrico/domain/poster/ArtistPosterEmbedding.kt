package com.lonx.lyrico.domain.poster

import com.lonx.audiotag.model.AudioPicture

/**
 * 把一张艺术家海报写进「这位艺术家的每一首歌」时要做的事。
 *
 * 和单曲编辑页一样，海报的归属靠标签里的 `description` 记录，所以同一张图写进不同的歌时，
 * 描述要看那首歌的艺术家字段：
 *
 * - 那首歌只有这位艺术家时描述留空。空描述在本应用里是「唯一艺术家」的既有写法，也让它不会
 *   因为艺术家字段以后改名而失联；
 * - 那首歌同时挂着别的艺术家时必须写名字，否则这张海报会和另一位艺术家的海报撞在一起——
 *   读取时描述为空的图片只能归给唯一艺术家，多艺术家歌曲里就变成了「对不上」。
 */
object ArtistPosterEmbedding {

    /**
     * 这首歌当前该用哪张艺术家海报；`null` 表示标签里已经就是它，不必再写一次。
     *
     * 返回 `null` 也让「用户授权后重跑」变成幂等的：已经写好的歌会被跳过，只补差下来的那些。
     */
    fun plan(
        pictures: List<AudioPicture>,
        artistNames: List<String>,
        artistName: String,
        data: ByteArray,
        mimeType: String
    ): ArtistPosterBinding? {
        val key = ArtistPosterGrouping.ownerKeyOf(artistName)
        val existing = ArtistPosterGrouping.entries(pictures, artistNames)
            .firstOrNull { it.artist != null && it.ownerKey == key }
            ?.picture

        // 描述为空、而这首歌又挂着多位艺术家时无法判断它属于谁，此时当作「没有」而新增一张，
        // 免得把另一位艺术家的海报改掉。
        if (existing != null &&
            existing.data.contentEquals(data) &&
            existing.mimeType == mimeType
        ) {
            return null
        }

        return ArtistPosterBinding(
            description = if (artistNames.size <= 1) "" else artistName.trim(),
            replaced = existing
        )
    }
}

/**
 * @param description 这张海报写进目标歌曲时该用的图片描述
 * @param replaced 这首歌里被替换掉的旧海报；为 `null` 表示新增，位置保持不动
 */
data class ArtistPosterBinding(
    val description: String,
    val replaced: AudioPicture?
)
