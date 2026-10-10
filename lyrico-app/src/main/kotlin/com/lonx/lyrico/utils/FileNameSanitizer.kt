package com.lonx.lyrico.utils

import com.lonx.lyrico.data.model.CharacterMappingRule

/**
 * 文件名非法字符清理工具
 */
object FileNameSanitizer {


    /**
     * 使用自定义映射规则清理文件名
     *
     * @param fileName 要清理的文件名
     * @param rules 映射规则列表（优先级从高到低）
     * @return 清理后的文件名
     */
    fun sanitize(fileName: String, rules: List<CharacterMappingRule>): String {
        var result = fileName

        // 应用所有启用的规则
        for (rule in rules) {
            if (!rule.isEnabled) continue
            result = replaceCharacters(result, rule.charMappings)
        }

        // 清理前后空格和重复的替换字符
        return result.trim()
    }

    /**
     * 将指定的字符替换为对应的目标字符
     */
    private fun replaceCharacters(
        input: String,
        charMappings: Map<String, String?>
    ): String {
        var result = input

        // 从后向前替换，避免索引偏移
        val indices = mutableListOf<Pair<IntRange, String?>>() // IntRange = 连续字符范围，String? = 替换值
        var i = 0

        while (i < result.length) {
            val char = result[i].toString()
            if (char in charMappings) {
                val start = i
                val replacement = charMappings[char]
                // 连续相同映射的字符可以合并
                while (i < result.length && charMappings[result[i].toString()] == replacement) {
                    i++
                }
                indices.add(IntRange(start, i - 1) to replacement)
            } else {
                i++
            }
        }

        // 从后向前替换
        for ((range, replacement) in indices.asReversed()) {
            val replaceStr = replacement ?: ""
            result = result.replaceRange(range.first, range.last + 1, replaceStr)
        }

        return result
    }
}
