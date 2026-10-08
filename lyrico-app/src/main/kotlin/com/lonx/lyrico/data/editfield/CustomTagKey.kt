package com.lonx.lyrico.data.editfield

import java.util.Locale

/**
 * 自定义标签键名的归一化规则。
 *
 * 扫描入库（[com.lonx.lyrico.data.repository.CustomTagKeyRepository]）与用户手动添加
 * 都走这里，保证"库里扫到的键"和"用户输入的键"是同一个字符串，不会因为大小写或首尾
 * 空白产生两个条目。
 */
object CustomTagKey {

    const val MAX_KEY_LENGTH = 64

    /** 去空白、转大写；不合法时返回 null。 */
    fun normalize(input: String): String? {
        val key = input.trim()

        return when {
            key.isBlank() -> null
            key.length > MAX_KEY_LENGTH -> null
            key.any { it == '\n' || it == '\r' } -> null
            else -> key.uppercase(Locale.ROOT)
        }
    }
}
