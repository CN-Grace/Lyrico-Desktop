package com.lonx.lyrico.ui.components.base

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class PillButtonSize {
    Small,
    Medium,
    Large
}

@Immutable
data class PillButtonStyle(
    val height: Dp,
    val contentPadding: PaddingValues,
    val iconOnlyPadding: PaddingValues,
    val spacing: Dp,
    val shape: Shape,
    val textStyle: TextStyle,
    val selectedTextStyle: TextStyle = textStyle
)

@Immutable
data class PillButtonColors(
    val selectedContainerColor: Color,
    val selectedContentColor: Color,
    val containerColor: Color,
    val contentColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color
) {
    fun containerColor(
        selected: Boolean,
        enabled: Boolean
    ): Color {
        return when {
            !enabled -> disabledContainerColor
            selected -> selectedContainerColor
            else -> containerColor
        }
    }

    fun contentColor(
        selected: Boolean,
        enabled: Boolean
    ): Color {
        return when {
            !enabled -> disabledContentColor
            selected -> selectedContentColor
            else -> contentColor
        }
    }
}

object PillButtonDefaults {

    @Composable
    fun style(size: PillButtonSize): PillButtonStyle {
        return when (size) {
            PillButtonSize.Small -> PillButtonStyle(
                height = 28.dp,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                iconOnlyPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
                spacing = 4.dp,
                shape = RoundedCornerShape(14.dp),
                textStyle = MiuixTheme.textStyles.body2.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                selectedTextStyle = MiuixTheme.textStyles.body2.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )

            PillButtonSize.Medium -> PillButtonStyle(
                height = 32.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                iconOnlyPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                spacing = 5.dp,
                shape = RoundedCornerShape(16.dp),
                textStyle = MiuixTheme.textStyles.body2.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                selectedTextStyle = MiuixTheme.textStyles.body2.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            PillButtonSize.Large -> PillButtonStyle(
                height = 38.dp,
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 7.dp),
                iconOnlyPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                spacing = 6.dp,
                shape = RoundedCornerShape(19.dp),
                textStyle = MiuixTheme.textStyles.body1.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                selectedTextStyle = MiuixTheme.textStyles.body1.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }

    @Composable
    fun colors(
        selectedContainerColor: Color = MiuixTheme.colorScheme.primary,
        selectedContentColor: Color = MiuixTheme.colorScheme.onPrimary,
        containerColor: Color = MiuixTheme.colorScheme.surface,
        contentColor: Color = MiuixTheme.colorScheme.onSurface,
        disabledContainerColor: Color = MiuixTheme.colorScheme.surface,
        disabledContentColor: Color = MiuixTheme.colorScheme.disabledOnSurface
    ): PillButtonColors {
        return PillButtonColors(
            selectedContainerColor = selectedContainerColor,
            selectedContentColor = selectedContentColor,
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor
        )
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    showText: Boolean = true,
    style: PillButtonStyle = PillButtonDefaults.style(PillButtonSize.Medium),
    colors: PillButtonColors = PillButtonDefaults.colors(),
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val containerColor = colors.containerColor(
        selected = selected,
        enabled = enabled
    )

    val contentColor = colors.contentColor(
        selected = selected,
        enabled = enabled
    )

    val hasText = showText && text.isNotBlank()
    val hasLeading = leading != null
    val hasTrailing = trailing != null
    val iconOnly = !hasText && (hasLeading || hasTrailing)

    Box(
        modifier = modifier
            .height(style.height)
            .clip(style.shape)
            .background(containerColor)
            .clickable(
                enabled = enabled,
                onClick = onClick
            )
            .padding(
                if (iconOnly) {
                    style.iconOnlyPadding
                } else {
                    style.contentPadding
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leading != null) {
                leading()
            }

            if (leading != null && hasText) {
                Spacer(modifier = Modifier.width(style.spacing))
            }

            if (hasText) {
                Text(
                    text = text,
                    style = if (selected) {
                        style.selectedTextStyle
                    } else {
                        style.textStyle
                    },
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (trailing != null && hasText) {
                Spacer(modifier = Modifier.width(style.spacing))
            }

            if (trailing != null) {
                trailing()
            }
        }
    }
}