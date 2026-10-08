package com.lonx.lyrico.utils

import java.util.Locale

/**
 * 歌词格式化工具类
 * 提供时间戳格式化、XML 转义等公共方法
 */
object LyricFormatter {
    
    // 匹配 LRC/Enhanced LRC 格式: [01:23.456] 或 <01:23.45>
    val LRC_TIME_PATTERN = Regex("([<\\[])(\\d{2,}):(\\d{2})\\.(\\d{2,3})([>\\]])")
    
    // 匹配 TTML 格式: begin="00:01:23.456" 或 end="00:01:23.456"
    val TTML_TIME_PATTERN = Regex("(begin=\"|end=\")(\\d{2,}):(\\d{2}):(\\d{2})\\.(\\d{2,3})(\")")

    /**
     * LRC 格式时间戳 (格式: mm:ss.SSS)
     *
     * 时间戳会写进歌词文件本身，所以格式必须与运行机器的区域设置无关：
     * 用默认区域碰到阿拉伯数字/土耳其语环境会写出非 ASCII 数字，破坏 LRC 结构。
     */
    fun formatTimestamp(millis: Long): String {
        val safeMillis = millis.coerceAtLeast(0L)
        val totalSeconds = safeMillis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val ms = safeMillis % 1000
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, ms)
    }

    /**
     * TTML 专属时间戳 (格式: HH:mm:ss.SSS)
     */
    fun formatTtmlTimestamp(millis: Long): String {
        val safeMillis = millis.coerceAtLeast(0L)
        val totalSeconds = safeMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val ms = safeMillis % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, ms)
    }

    /**
     * XML 字符转义（防止歌词中的特殊字符破坏 TTML 结构）
     */
    fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * XML 字符反转义
     */
    fun unescapeXml(text: String): String {
        return text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    /**
     * 计算应用偏移量，保证结果大于等于 0
     */
    fun applyOffset(time: Long, offset: Long): Long {
        return (time + offset).coerceAtLeast(0L)
    }
}
