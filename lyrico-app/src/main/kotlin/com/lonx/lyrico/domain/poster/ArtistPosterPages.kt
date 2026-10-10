package com.lonx.lyrico.domain.poster

import com.lonx.audiotag.model.AudioPicture

/**
 * 封面区里艺术家区域的一页。
 *
 * @param artistName 这一页归属的艺术家；空串表示艺术家字段没填
 * @param entry 标签里的内嵌海报；为 null 表示这一页只有外置海报文件夹可以依靠
 * @param showExternalPoster 是否去外置海报文件夹里找这位艺术家的海报文件
 */
data class ArtistPosterPage(
    val artistName: String,
    val entry: ArtistPictureEntry?,
    val showExternalPoster: Boolean
)

/**
 * 决定艺术家区域显示哪几页。
 *
 * 内嵌海报和外置海报文件夹是同一个「一位艺术家一张海报」的两个来源，所以两者必须按同一套
 * 归属规则来分配：外置海报文件夹是按**文件名**认艺术家的，把整段艺术家字段（`A/B/C`）丢过去
 * 永远匹配不到 `A.jpg`，多艺术家歌曲就会一张外置海报都用不上。
 */
object ArtistPosterPages {

    /**
     * 一位艺术家一个海报位：
     * 1. 按艺术家字段的顺序，每位艺术家占一页，放它自己的内嵌海报（历史遗留的多张也全部列出）；
     * 2. 没有内嵌海报的艺术家，这一页交给外置海报文件夹兜底——但原始标签里本来有、被用户删掉的
     *    除外，那种情况留空位，不要又从文件夹里变出一张来；
     * 3. 描述对不上任何艺术家的内嵌海报各自占一页（用户可以在菜单里重新指定归属）；
     * 4. 一个位都没有时也要留一个空位，否则用户没地方添加。
     */
    fun build(
        pictures: List<AudioPicture>,
        original: List<AudioPicture>,
        artistNames: List<String>
    ): List<ArtistPosterPage> {
        val entries = ArtistPosterGrouping.entries(pictures, artistNames)
        val originalKeys = ArtistPosterGrouping.entries(original, artistNames)
            .map { it.ownerKey }
            .toSet()

        val pages = mutableListOf<ArtistPosterPage>()
        artistNames.forEach { artistName ->
            val key = ArtistPosterGrouping.ownerKeyOf(artistName)
            val owned = entries.filter { it.artist != null && it.ownerKey == key }
            if (owned.isNotEmpty()) {
                owned.forEach { pages += ArtistPosterPage(artistName, it, showExternalPoster = false) }
            } else {
                pages += ArtistPosterPage(
                    artistName = artistName,
                    entry = null,
                    showExternalPoster = key !in originalKeys
                )
            }
        }

        entries.filter { it.artist == null }
            .forEach { pages += ArtistPosterPage(it.ownerName, it, showExternalPoster = false) }

        if (pages.isEmpty()) {
            pages += ArtistPosterPage(artistName = "", entry = null, showExternalPoster = true)
        }
        return pages
    }
}
