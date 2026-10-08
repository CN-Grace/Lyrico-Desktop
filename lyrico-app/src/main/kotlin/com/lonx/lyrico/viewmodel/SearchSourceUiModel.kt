package com.lonx.lyrico.viewmodel

import com.lonx.lyrico.data.model.lyrics.SearchSource
import com.lonx.lyrico.data.model.plugin.PluginCapability
import org.jetbrains.compose.resources.StringResource

data class SearchSourceUiModel(
    val id: String,
    val name: String,
    val iconPath: String? = null,
    val supportsLyrics: Boolean = false,
    // Was `@param:StringRes val labelRes: Int?` on Android. Never actually assigned anywhere -- both
    // trees leave it at the default -- but the type is converted rather than the field deleted, so the
    // screens that may want it later compile against the desktop resource type from the start.
    val labelRes: StringResource? = null
)

fun SearchSource.toUiModel(): SearchSourceUiModel {
    return SearchSourceUiModel(
        id = id,
        name = name,
        iconPath = iconPath,
        supportsLyrics = PluginCapability.GET_LYRICS in capabilities
    )
}
