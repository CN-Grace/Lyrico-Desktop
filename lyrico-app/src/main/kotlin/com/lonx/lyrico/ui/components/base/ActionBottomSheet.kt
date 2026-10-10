package com.lonx.lyrico.ui.components.base

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.window.WindowBottomSheet

@Composable
fun ActionBottomSheet(
    show: Boolean,
    title: String? = null,
    enableNestedScroll: Boolean = true,
    allowDismiss: Boolean = true,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
    startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
) {
    WindowBottomSheet(
        show = show,
        enableNestedScroll = enableNestedScroll,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        allowDismiss = allowDismiss,
        title = title,
        startAction = {
            startAction?.invoke()
        },
        endAction = {
            endAction?.invoke()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            content()
        }
    }
}