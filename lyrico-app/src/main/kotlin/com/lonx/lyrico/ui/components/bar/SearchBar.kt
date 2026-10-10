package com.lonx.lyrico.ui.components.bar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun InputField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    onSearch: ((String) -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val textColor = MiuixTheme.colorScheme.onSurface
    val textStyle = MiuixTheme.textStyles.paragraph.copy(color = textColor)

    // 仅处理自动聚焦，不操作光标
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(100)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val defaultLeadingIcon: @Composable () -> Unit = {
        Icon(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            imageVector = MiuixIcons.Basic.Search,
            tint = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            contentDescription = null
        )
    }

    val defaultTrailingIcon: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = state.text.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            indication = null,
                            interactionSource = interactionSource
                        ) {
                            // clearText() 内部会正确处理光标
                            state.clearText()
                        },
                    imageVector = MiuixIcons.Basic.SearchCleanup,
                    tint = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    contentDescription = "Clear"
                )
            }
        }
    }

    BasicTextField(
        state = state,
        enabled = enabled,
        textStyle = textStyle,
        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = {
            onSearch?.invoke(state.text.toString())
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interactionSource,
        modifier = modifier.focusRequester(focusRequester),
        decorator = { innerTextField ->
            Box(
                modifier = Modifier.background(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(50),
                ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (leadingIcon ?: defaultLeadingIcon)()

                    Box(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .heightIn(min = 45.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.text.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = MiuixTheme.textStyles.main.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerHigh
                                ),
                                maxLines = 1
                            )
                        }
                        innerTextField()
                    }

                    (trailingIcon ?: defaultTrailingIcon)()
                }
            }
        },
    )
}

@Composable
fun SearchBar(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    actions: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    onSearch: ((String) -> Unit)? = null,
    autoFocus: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InputField(
            state = state,
            placeholder = placeholder,
            onSearch = onSearch,
            autoFocus = autoFocus,
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth(),
            trailingIcon = trailingIcon
        )
        actions?.invoke()
    }
}