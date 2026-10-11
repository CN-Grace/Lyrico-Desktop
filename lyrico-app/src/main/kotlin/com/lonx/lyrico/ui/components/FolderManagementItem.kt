package com.lonx.lyrico.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.cd_more_actions
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FolderManagementItem(
    name: String,
    path: String,
    status: String,
    actions: DropdownEntry,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        BasicComponent(
            endActions = {
                OverlayIconDropdownMenu(entry = actions, enabled = enabled) {
                    Icon(MiuixIcons.More, stringResource(Res.string.cd_more_actions))
                }
            },
            bottomAction = {
                AnimatedVisibility(visible = isLoading) { LinearProgressIndicator() }
            },
            enabled = enabled,
            onClick = onClick
        ) {
            Text(name, color = MiuixTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(path, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = MiuixTheme.textStyles.body2.fontSize)
            Spacer(Modifier.height(6.dp))
            Text(status, color = MiuixTheme.colorScheme.onSurfaceVariantActions, fontSize = MiuixTheme.textStyles.body2.fontSize)
        }
    }
}
