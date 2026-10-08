package com.lonx.lyrico.data.model.log

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.log_retention_30_days
import com.lonx.lyrico.resources.log_retention_7_days
import com.lonx.lyrico.resources.log_retention_90_days
import com.lonx.lyrico.resources.log_retention_forever
import com.lonx.lyrico.resources.log_retention_none
import org.jetbrains.compose.resources.StringResource

enum class LogRetentionOption(
    val labelRes: StringResource,
    val retentionMillis: Long?
) {
    NONE(Res.string.log_retention_none, null),
    SEVEN_DAYS(Res.string.log_retention_7_days, 7L * 24L * 60L * 60L * 1000L),
    THIRTY_DAYS(Res.string.log_retention_30_days, 30L * 24L * 60L * 60L * 1000L),
    NINETY_DAYS(Res.string.log_retention_90_days, 90L * 24L * 60L * 60L * 1000L),
    FOREVER(Res.string.log_retention_forever, Long.MAX_VALUE);

    val isRecordingEnabled: Boolean
        get() = this != NONE
}