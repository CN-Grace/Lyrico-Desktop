package com.lonx.lyrico.data.model

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.search_source_tab_style_icon_and_text
import com.lonx.lyrico.resources.search_source_tab_style_icon_only
import com.lonx.lyrico.resources.search_source_tab_style_text_only
import com.lonx.lyrico.data.repository.SettingsDefaults
import org.jetbrains.compose.resources.StringResource

enum class SearchSourceTabStyle(
    val labelRes: StringResource
) {
    ICON_ONLY(Res.string.search_source_tab_style_icon_only),
    TEXT_ONLY(Res.string.search_source_tab_style_text_only),
    ICON_AND_TEXT(Res.string.search_source_tab_style_icon_and_text)
}

/**
 * 搜索相关配置，用于 SearchViewModel 等需要搜索参数的消费者
 */
data class SearchConfig(
    val separator: String = SettingsDefaults.SEPARATOR,
    val searchSourceOrder: List<String> = SettingsDefaults.SEARCH_SOURCE_ORDER,
    val enabledSearchSources: Set<String> = SettingsDefaults.DEFAULT_ENABLED_SEARCH_SOURCES,
    val searchPageSize: Int = SettingsDefaults.SEARCH_PAGE_SIZE,
    val searchSourceTabStyle: SearchSourceTabStyle = SettingsDefaults.SEARCH_SOURCE_TAB_STYLE,
    val showAllSearchResultFields: Boolean = SettingsDefaults.SHOW_ALL_SEARCH_RESULT_FIELDS
)
