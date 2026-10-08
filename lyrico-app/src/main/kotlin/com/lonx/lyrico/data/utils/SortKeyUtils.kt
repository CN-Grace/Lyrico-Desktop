package com.lonx.lyrico.data.utils

import com.github.houbb.pinyin.constant.enums.PinyinStyleEnum
import com.github.houbb.pinyin.util.PinyinHelper

object SortKeyUtils {

    data class SortKeys(
        val groupKey: String,
        val sortKey: String
    )

    fun getSortKeys(text: String): SortKeys {
        val raw = text.trim()
        if (raw.isBlank()) {
            return SortKeys("#", "2_")
        }

        val firstChar = raw[0]

        // 数字
        if (firstChar.isDigit()) {
            return SortKeys("0", "0_$raw")
        }

        // ASCII 字母
        if (firstChar.isLetter() && firstChar.code < 128) {
            val sortKey = raw.uppercase()
            val groupKey = sortKey[0].toString()
            return SortKeys(groupKey, "1_$sortKey")
        }

        // 拼音。Android 用的 tinypinyin 只以 AAR 形式发布在 jcenter 上，Maven Central 没有，
        // 桌面端改用 houbb 的纯 JVM 拼音库：它自带字符表 + 词组表（认「重庆」这类多音词），
        // 输出与 tinypinyin 的小写全拼一致，再统一大写。
        val pinyinFull = try {
            PinyinHelper.toPinyin(raw, PinyinStyleEnum.NORMAL, "")
        } catch (e: Exception) {
            ""
        }

        if (pinyinFull.isNotBlank()) {
            val sortKey = pinyinFull.uppercase()
            val firstPinyinChar = sortKey[0]

            if (firstPinyinChar in 'A'..'Z') {
                return SortKeys(
                    firstPinyinChar.toString(),
                    "1_$sortKey"
                )
            }
        }

        // 兜底 #
        return SortKeys("#", "2_$raw")
    }
}
