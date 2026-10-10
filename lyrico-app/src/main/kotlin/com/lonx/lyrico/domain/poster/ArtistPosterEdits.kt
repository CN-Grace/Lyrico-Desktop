package com.lonx.lyrico.domain.poster

import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.artistPictureTypes
import com.lonx.audiotag.model.type

/**
 * 艺术家海报的纯数据变换。
 *
 * 这些操作原本散在 ViewModel 里，先后踩过「用下标当身份」「按归属键在原始/当前两侧各自解析」
 * 两个坑；抽成纯函数后可以直接单测，不必等到界面上才发现。
 *
 * 约定：图片用**实例**（`===`）标识目标，列表每次都是新的，未改动的元素仍是同一批实例。
 */
object ArtistPosterEdits {

    /** 把 [artistName] 名下的海报换成 [picture]（一个艺术家只保留一张），位置不变。 */
    fun setFor(
        pictures: List<AudioPicture>,
        artistNames: List<String>,
        artistName: String,
        picture: AudioPicture
    ): List<AudioPicture> {
        val key = ArtistPosterGrouping.ownerKeyOf(artistName)
        val replacedIndex = pictures.indexOfFirst { it.ownerKeyIn(artistNames) == key }
        if (replacedIndex < 0) return pictures + picture

        return pictures.mapIndexedNotNull { index, existing ->
            when {
                index == replacedIndex -> picture
                // 早期版本允许同一艺术家多张，一并合并掉
                existing.ownerKeyIn(artistNames) == key -> null
                else -> existing
            }
        }
    }

    /** 只删掉 [target] 这一张。 */
    fun remove(pictures: List<AudioPicture>, target: AudioPicture): List<AudioPicture> {
        if (pictures.none { it === target }) return pictures
        return pictures.filterNot { it === target }
    }

    /** 只替换 [target] 的图片数据，保留它的归属描述。 */
    fun replaceData(
        pictures: List<AudioPicture>,
        target: AudioPicture,
        data: ByteArray,
        mimeType: String
    ): List<AudioPicture> {
        if (pictures.none { it === target }) return pictures
        return pictures.map { picture ->
            if (picture === target) picture.copy(data = data, mimeType = mimeType) else picture
        }
    }

    /**
     * 把 [target] 改挂到 [artistName]，同时把该艺术家原有的那张换到 [target] 原来的归属上。
     *
     * 用于「目标艺术家已经有海报」时用户选择**交换归属**：两张图的描述互换，谁都不丢。
     * 每一侧只处理一张（历史遗留的同名多张仍按原样留着）。
     */
    fun swapOwners(
        pictures: List<AudioPicture>,
        artistNames: List<String>,
        target: AudioPicture,
        artistName: String
    ): List<AudioPicture> {
        val entries = ArtistPosterGrouping.entries(pictures, artistNames)
        val targetEntry = entries.firstOrNull { it.picture === target } ?: return pictures
        val nextKey = ArtistPosterGrouping.ownerKeyOf(artistName)
        val other = entries.firstOrNull { it.picture !== target && it.ownerKey == nextKey }
            ?: return pictures

        return pictures.map { picture ->
            when {
                picture === target -> picture.copy(description = artistName.trim())
                picture === other.picture -> picture.copy(description = targetEntry.description)
                else -> picture
            }
        }
    }

    /**
     * 把 [target] 所在的归属整组改挂到 [artistName]；该艺术家原有的海报会被顶掉。
     */
    fun reassign(
        pictures: List<AudioPicture>,
        artistNames: List<String>,
        target: AudioPicture,
        artistName: String
    ): List<AudioPicture> {
        val entries = ArtistPosterGrouping.entries(pictures, artistNames)
        val sourceKey = entries.firstOrNull { it.picture === target }?.ownerKey ?: return pictures
        val nextKey = ArtistPosterGrouping.ownerKeyOf(artistName)
        val dropped = entries
            .filter { it.picture !== target && (it.ownerKey == sourceKey || it.ownerKey == nextKey) }
            .map { it.picture }

        return pictures
            .filterNot { picture -> dropped.any { it === picture } }
            .map { picture ->
                if (picture === target) picture.copy(description = artistName.trim()) else picture
            }
    }

    /**
     * 把所有艺术家海报还原成 [original] 里的样子，其余图片保持用户当前的样子。
     *
     * **不**做逐张「找到它的原图」的启发式匹配：重挂会同时删掉目标艺术家原来的海报、裁剪会改数据、
     * 两个都做过就无法识别，任何启发式都会时而丢图、时而把别的艺术家的图恢复过来。整组还原是
     * 唯一能保证正确的语义，代价是它会连同其它艺术家的海报一起还原。
     *
     * 顺序按**原始布局**放回去：先给每张仍在的封面/封底等图片找回它在原始列表里的位置，再把
     * 艺术家图片插到「原始位置在它之后的第一张仍在的图片」之前，后方没有锚点则放末尾。
     *
     * 这一点不是洁癖：`frontCoverOrFallback()` 在没有标准封面（FrontCover/Other）时会退回
     * 「第一张图」，布局变了那一页就会显示成另一张图，看起来就是「还原之后图片对应不上了」。
     * 只按当前顺序就地替换也能把图片找回来，但会把这种文件的首图换掉。
     */
    fun revertAll(
        pictures: List<AudioPicture>,
        original: List<AudioPicture>
    ): List<AudioPicture> {
        val currentOtherPictures = pictures.filterNot { it.type in artistPictureTypes }
        val originalOtherPictures = original.withIndex()
            .filter { it.value.type !in artistPictureTypes }
        val usedOriginalIndexes = mutableSetOf<Int>()

        // 为当前仍存在的非艺术家图片寻找原始位置。未修改的图片保留同一实例；替换过的封面
        // 则退到类型与描述匹配。映射只用于确定艺术家图片应插在哪两个当前图片之间。
        val originalIndexByCurrentOther = currentOtherPictures.map { current ->
            fun match(predicate: (AudioPicture) -> Boolean): Int? =
                originalOtherPictures.firstOrNull { indexed ->
                    indexed.index !in usedOriginalIndexes && predicate(indexed.value)
                }?.index?.also(usedOriginalIndexes::add)

            match { it === current }
                ?: match { it == current }
                ?: match {
                    it.type == current.type &&
                        it.description.equals(current.description, ignoreCase = true)
                }
        }

        val artistsByBoundary = mutableMapOf<Int, MutableList<AudioPicture>>()
        original.forEachIndexed { originalIndex, picture ->
            if (picture.type !in artistPictureTypes) return@forEachIndexed

            // 放到原始位置之后第一个仍存在的非艺术家图片之前；如果后方没有锚点，放在末尾。
            // 这样删除前置封面不会把后面的背面图挪到艺术家图片之前。
            val boundary = originalIndexByCurrentOther.indexOfFirst { mappedIndex ->
                mappedIndex != null && mappedIndex > originalIndex
            }.takeIf { it >= 0 } ?: currentOtherPictures.size
            artistsByBoundary.getOrPut(boundary, ::mutableListOf) += picture
        }

        return buildList(currentOtherPictures.size + original.count { it.type in artistPictureTypes }) {
            for (boundary in 0..currentOtherPictures.size) {
                addAll(artistsByBoundary[boundary].orEmpty())
                if (boundary < currentOtherPictures.size) {
                    add(currentOtherPictures[boundary])
                }
            }
        }
    }

    private fun AudioPicture.ownerKeyIn(artistNames: List<String>): String? =
        ArtistPosterGrouping.ownerKeyOf(this, artistNames)
}
