package com.lonx.lyrico.ui.components.base


import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.cancel
import com.lonx.lyrico.resources.confirm
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun YesNoBottomSheet(
    show: Boolean,
    title: String? = null,
    enableNestedScroll: Boolean = true,
    allowDismiss: Boolean = true,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit = {},
    onCancel: () -> Unit = onDismissRequest,
    onConfirm: () -> Unit,
    cancelText: String = stringResource(Res.string.cancel),
    confirmText: String = stringResource(Res.string.confirm),
    content: @Composable ColumnScope.() -> Unit,
) {
    ActionBottomSheet(
        show = show,
        title = title,
        enableNestedScroll = enableNestedScroll,
        allowDismiss = allowDismiss,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        content = content,
        startAction =  {
            TextButton(
                colors = ButtonColors(
                    containerColor = MiuixTheme.colorScheme.surface,
                    contentColor = MiuixTheme.colorScheme.onSurface,
                    disabledContainerColor = MiuixTheme.colorScheme.surface,
                    disabledContentColor = MiuixTheme.colorScheme.disabledOnSurface
                ),
                onClick = {
                    onCancel()
                }
            ){
                Text(text = cancelText)
            }
        },
        endAction =  {
            TextButton(
                colors = ButtonColors(
                    containerColor = MiuixTheme.colorScheme.surface,
                    contentColor = MiuixTheme.colorScheme.primary,
                    disabledContainerColor = MiuixTheme.colorScheme.surface,
                    disabledContentColor = MiuixTheme.colorScheme.disabledPrimary
                ),
                onClick = {
                    onConfirm()
                }
            ){
                Text(text = confirmText, color = MiuixTheme.colorScheme.primary)
            }
        }
    )
}