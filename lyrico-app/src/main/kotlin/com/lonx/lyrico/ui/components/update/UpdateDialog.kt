package com.lonx.lyrico.ui.components.update

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.dialog_action_go_update
import com.lonx.lyrico.resources.dialog_title_update_available
import com.lonx.lyrico.ui.components.base.YesNoDialog
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.ui.components.MiuixMarkdownText
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun UpdateDialog(
    show: Boolean,
    versionName: String,
    releaseNote: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    val scrollState = rememberScrollState()

    YesNoDialog(
        show = show,
        onConfirm = onConfirm,
        onDismissRequest = onDismissRequest,
        title = formattedStringResource(
            Res.string.dialog_title_update_available,
            versionName
        ),
        confirmText = stringResource(Res.string.dialog_action_go_update),
        content = {
            Card(
                modifier = Modifier.padding(bottom = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.secondaryContainer
                )
            ) {
                MiuixMarkdownText(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(scrollState),
                    markdown = releaseNote
                )
            }
        }
    )
}