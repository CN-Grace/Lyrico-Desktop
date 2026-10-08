package com.lonx.lyrico.data.model.log

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.app_log_type_app
import com.lonx.lyrico.resources.app_log_type_batch
import com.lonx.lyrico.resources.app_log_type_crash
import com.lonx.lyrico.resources.app_log_type_database
import com.lonx.lyrico.resources.app_log_type_metadata
import com.lonx.lyrico.resources.app_log_type_network
import com.lonx.lyrico.resources.app_log_type_plugin
import org.jetbrains.compose.resources.StringResource

enum class AppLogType(
    val labelRes: StringResource
) {
    APP(Res.string.app_log_type_app),
    CRASH(Res.string.app_log_type_crash),
    METADATA(Res.string.app_log_type_metadata),
    BATCH(Res.string.app_log_type_batch),
    DATABASE(Res.string.app_log_type_database),
    NETWORK(Res.string.app_log_type_network),
    PLUGIN(Res.string.app_log_type_plugin)
}
