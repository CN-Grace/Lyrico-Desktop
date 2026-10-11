package com.lonx.lyrico.data.model

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.language_english
import com.lonx.lyrico.resources.language_simplified_chinese
import com.lonx.lyrico.resources.language_system
import com.lonx.lyrico.resources.language_traditional_chinese_hong_kong
import com.lonx.lyrico.resources.language_traditional_chinese_taiwan
import org.jetbrains.compose.resources.StringResource

/**
 * 应用语言。`languageTag` 是 BCP 47 标签，`SYSTEM` 用空串表示「跟随系统」。
 *
 * 安卓版把选中的语言交给 AppCompat 存（`AppCompatDelegate.setApplicationLocales`），桌面版没有
 * 那一层，改成存在设置里（`SettingsRepository.appLanguage`），由 `ProvideAppLocale` 在界面上生效。
 */
enum class AppLanguage(val languageTag: String, val labelRes: StringResource) {
    SYSTEM("", Res.string.language_system),
    ENGLISH("en", Res.string.language_english),
    SIMPLIFIED_CHINESE("zh-CN", Res.string.language_simplified_chinese),
    TRADITIONAL_CHINESE_TAIWAN("zh-TW", Res.string.language_traditional_chinese_taiwan),
    TRADITIONAL_CHINESE_HONG_KONG("zh-HK", Res.string.language_traditional_chinese_hong_kong),
    ;

    companion object {
        /**
         * 从存下来的标签还原。认不出的标签（旧版本写的、手改过的）退回 [SYSTEM]，而不是抛异常：
         * 一个坏掉的偏好值不该让界面起不来。
         */
        fun fromLanguageTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.languageTag == tag } ?: SYSTEM
    }
}
