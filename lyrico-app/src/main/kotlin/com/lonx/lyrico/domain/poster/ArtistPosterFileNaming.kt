package com.lonx.lyrico.domain.poster

import com.lonx.lyrico.data.model.CharacterMappingDefaults
import com.lonx.lyrico.utils.FileNameSanitizer
import com.lonx.lyrico.utils.coil.ArtistPosterMatcher

/**
 * 把一张海报存进艺术家海报文件夹时，文件名该叫什么。
 *
 * 外置海报是**按文件名认人**的（见 [ArtistPosterMatcher]），所以文件名必须写成艺术家名，
 * 而不是用户随便起的名字；艺术家名里那些文件名放不下的字符，按本应用重命名文件时的同一套
 * 规则换成全角字符，这样 `AC/DC` 存成 `AC／DC.jpg` 后依然能被认出来。
 */
object ArtistPosterFileNaming {

    private val mimeExtensions = mapOf(
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "video/mp4" to "mp4"
    )

    /**
     * 艺术家名清成可用作文件名的词干；清理后为空（例如名字全是非法字符）时返回 `null`。
     */
    fun stemFor(artistName: String): String? = FileNameSanitizer
        .sanitize(artistName, CharacterMappingDefaults.ALL_BUILTIN_RULES)
        .trim()
        .trimEnd('.')
        .takeIf { it.isNotBlank() }

    /**
     * 按图片的 MIME 类型给出扩展名，取不到时退回 [fileName] 自己的扩展名，再退回 `jpg`。
     */
    fun extensionFor(mimeType: String?, fileName: String? = null): String {
        val normalized = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        mimeExtensions[normalized]?.let { return it }

        val fromName = fileName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all { char -> char.isLetterOrDigit() } }
        return fromName ?: "jpg"
    }

    /**
     * 文件夹里已经属于 [artistName] 的海报文件名，最好的那个排在最前。
     *
     * 排序与读取海报时一致（先按匹配等级，再按名字），所以这里选中的文件就是界面正在显示的那一张。
     */
    fun matchesArtist(fileNames: List<String>, artistName: String): List<String> = fileNames
        .mapNotNull { name -> ArtistPosterMatcher.rank(name, artistName)?.let { rank -> rank to name } }
        .sortedWith(compareBy({ it.first }, { it.second.lowercase() }, { it.second }))
        .map { it.second }
}
