package com.lonx.lyrico.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.common_delete
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 胶囊换行网格，规则类页面共用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipGrid(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/**
 * 自定义标签、非歌词内容过滤规则、艺术家拆分规则三个页面共用的胶囊。
 *
 * [checked] 为 null 时不渲染勾选圈，胶囊始终按"已生效"着色，适合只有一个动作
 * （点击编辑或直接删除）的规则；传入 true/false 时会渲染勾选圈，并按状态切换高亮，
 * 适合可开关的规则。
 */
@Composable
fun ManagedChip(
    text: String,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val showCheckbox = checked != null
    val highlighted = checked != false
    val colorScheme = MiuixTheme.colorScheme

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (highlighted) {
                    colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    colorScheme.secondaryContainer
                }
            )
            .border(
                width = 1.dp,
                color = if (highlighted) {
                    colorScheme.primary.copy(alpha = 0.48f)
                } else {
                    colorScheme.onSurfaceVariantActions.copy(alpha = 0.16f)
                },
                shape = RoundedCornerShape(50),
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = if (showCheckbox) 8.dp else 10.dp,
                top = 6.dp,
                end = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheckbox) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(
                        if (highlighted) {
                            colorScheme.primary
                        } else {
                            colorScheme.onSurfaceVariantActions.copy(alpha = 0.16f)
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (highlighted) {
                    Text(
                        text = "✓",
                        color = colorScheme.onPrimary,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                    )
                }
            }
        }

        Text(
            text = text,
            modifier = Modifier.padding(
                start = if (showCheckbox) 8.dp else 0.dp,
                end = 8.dp,
            ),
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (onDelete != null) {
            Icon(
                imageVector = MiuixIcons.Delete,
                contentDescription = stringResource(Res.string.common_delete),
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                tint = colorScheme.onSurfaceVariantActions,
            )
        }
    }
}
