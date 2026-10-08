package com.lonx.lyrico.data.model

import androidx.compose.runtime.Composable
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.replacement_ampersand
import com.lonx.lyrico.resources.replacement_full_width_asterisk
import com.lonx.lyrico.resources.replacement_full_width_backslash
import com.lonx.lyrico.resources.replacement_full_width_colon
import com.lonx.lyrico.resources.replacement_full_width_comma
import com.lonx.lyrico.resources.replacement_full_width_greater_than_sign
import com.lonx.lyrico.resources.replacement_full_width_less_than_sign
import com.lonx.lyrico.resources.replacement_full_width_question_mark
import com.lonx.lyrico.resources.replacement_full_width_quotation_mark
import com.lonx.lyrico.resources.replacement_full_width_slash
import com.lonx.lyrico.resources.replacement_full_width_vertical_bar
import com.lonx.lyrico.resources.replacement_half_width_comma
import com.lonx.lyrico.resources.replacement_ideographic_comma
import com.lonx.lyrico.resources.replacement_remove
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * 字符映射规则
 *
 * @param id 规则ID（唯一标识）
 * @param name 规则名称
 * @param charMappings 字符映射表：key = 原字符，value = 替换字符（null表示移除）
 * @param description 规则描述
 * @param isBuiltIn 是否为内置规则
 * @param isEnabled 是否启用该规则
 */
@Serializable
data class CharacterMappingRule(
    val id: String,
    val name: String,
    val charMappings: Map<String, String?> = emptyMap(),
    val description: String = "",
    val isBuiltIn: Boolean = false,
    val isEnabled: Boolean = true
)

/**
 * 字符映射配置
 *
 * @param rules 所有映射规则（内置规则）
 */
@Serializable
data class CharacterMappingConfig(
    val rules: List<CharacterMappingRule> = emptyList()
)
enum class ReplacementCharOption(
    val value: String,
    val labelRes: StringResource
) {
    REMOVE(
        value = "",
        labelRes = Res.string.replacement_remove
    ),

    IDEOGRAPHIC_COMMA(
        value = "、",
        labelRes = Res.string.replacement_ideographic_comma
    ),

    HALF_WIDTH_COMMA(
        value = ",",
        labelRes = Res.string.replacement_half_width_comma
    ),

    FULL_WIDTH_COMMA(
        value = "，",
        labelRes = Res.string.replacement_full_width_comma
    ),

    FULL_WIDTH_BACKSLASH(
        value = "＼",
        labelRes = Res.string.replacement_full_width_backslash
    ),

    FULL_WIDTH_SLASH(
        value = "／",
        labelRes = Res.string.replacement_full_width_slash
    ),

    FULL_WIDTH_COLON(
        value = "：",
        labelRes = Res.string.replacement_full_width_colon
    ),

    FULL_WIDTH_ASTERISK(
        value = "＊",
        labelRes = Res.string.replacement_full_width_asterisk
    ),

    FULL_WIDTH_QUESTION_MARK(
        value = "？",
        labelRes = Res.string.replacement_full_width_question_mark
    ),

    FULL_WIDTH_QUOTATION_MARK(
        value = "＂",
        labelRes = Res.string.replacement_full_width_quotation_mark
    ),

    FULL_WIDTH_LESS_THAN_SIGN(
        value = "＜",
        labelRes = Res.string.replacement_full_width_less_than_sign
    ),

    FULL_WIDTH_GREATER_THAN_SIGN(
        value = "＞",
        labelRes = Res.string.replacement_full_width_greater_than_sign
    ),

    FULL_WIDTH_VERTICAL_BAR(
        value = "｜",
        labelRes = Res.string.replacement_full_width_vertical_bar
    ),

    AMPERSAND(
        value = "&",
        labelRes = Res.string.replacement_ampersand
    );

    companion object {
        fun fromValue(value: String?): ReplacementCharOption? {
            return entries.find { it.value == value }
        }
    }
}
fun String?.toReplacementOption(): ReplacementCharOption? {
    return ReplacementCharOption.fromValue(this)
}
/**
 * 预定义的替换字符选项
 */
@Composable
fun ReplacementCharOption.displayName(): String {
    return stringResource(labelRes)
}

object CharacterMappingDefaults {
    // 系统默认的非法字符规则 - 使用新的 charMappings 方式
    val DEFAULT_INVALID_CHARS = CharacterMappingRule(
        id = "default_invalid_chars",
        name = "文件系统非法字符",
        charMappings = mapOf(
            "\\" to "＼",
            "/" to "／",
            ":" to "：",
            "*" to "＊",
            "?" to "？",
            "\"" to "＂",
            "<" to "＜",
            ">" to "＞",
            "|" to "｜"
        ),
        description = "Windows/Linux文件系统中的非法字符",
        isBuiltIn = true,
        isEnabled = true
    )

    val ALL_BUILTIN_RULES = listOf(
        DEFAULT_INVALID_CHARS
    )
}
