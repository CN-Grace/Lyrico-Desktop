package com.lonx.lyrico.data.model.log

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.app_log_level_debug
import com.lonx.lyrico.resources.app_log_level_error
import com.lonx.lyrico.resources.app_log_level_info
import com.lonx.lyrico.resources.app_log_level_warning
import org.jetbrains.compose.resources.StringResource

enum class AppLogLevel(
    val labelRes: StringResource
) {
    DEBUG(Res.string.app_log_level_debug),
    INFO(Res.string.app_log_level_info),
    WARNING(Res.string.app_log_level_warning),
    ERROR(Res.string.app_log_level_error)
}
