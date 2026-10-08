package com.lonx.lyrico.data.model.search

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.search_type_album
import com.lonx.lyrico.resources.search_type_all
import com.lonx.lyrico.resources.search_type_artist
import com.lonx.lyrico.resources.search_type_filename
import com.lonx.lyrico.resources.search_type_title
import org.jetbrains.compose.resources.StringResource

enum class LocalSearchType(val value: String, val labelRes: StringResource) {
    ALL("ALL", Res.string.search_type_all),
    TITLE("TITLE", Res.string.search_type_title),
    ARTIST("ARTIST", Res.string.search_type_artist),
    ALBUM("ALBUM", Res.string.search_type_album),
    FILENAME("FILE_NAME", Res.string.search_type_filename)
}