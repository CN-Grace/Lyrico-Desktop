package com.lonx.lyrico.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.ui.components.blur.BlurredTopBar
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.add_non_lyrics_cleanup_rule
import com.lonx.lyrico.resources.cancel
import com.lonx.lyrico.resources.common_delete
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.edit_non_lyrics_cleanup_rule
import com.lonx.lyrico.resources.invalid_or_duplicate_rule
import com.lonx.lyrico.resources.non_lyrics_cleanup_delete_rule_message
import com.lonx.lyrico.resources.non_lyrics_cleanup_delete_rule_title
import com.lonx.lyrico.resources.non_lyrics_cleanup_rule_input_hint
import com.lonx.lyrico.resources.non_lyrics_cleanup_rules_hint
import com.lonx.lyrico.resources.non_lyrics_cleanup_rules_section
import com.lonx.lyrico.resources.non_lyrics_cleanup_rules_title
import com.lonx.lyrico.resources.reset_to_default
import com.lonx.lyrico.ui.components.ChipGrid
import com.lonx.lyrico.ui.components.ManagedChip
import com.lonx.lyrico.ui.components.scaffoldContentPadding
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.viewmodel.SettingsViewModel
import com.lonx.lyrico.utils.formattedStringResource
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun LyricsCleanupRulesScreen(
    navigator: Navigator
) {
    val viewModel: SettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    var addingRule by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<String?>(null) }
    var deletingRule by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val invalidOrDuplicateRule = stringResource(Res.string.invalid_or_duplicate_rule)

    val topBarBackdrop = rememberBarBlurBackdrop()
    Scaffold(
        topBar = {
            BlurredTopBar(backdrop = topBarBackdrop) {
                SmallTopAppBar(
                    color = Color.Transparent,
                    defaultWindowInsetsPadding = false,
                    title = stringResource(Res.string.non_lyrics_cleanup_rules_title),
                    navigationIcon = {
                        IconButton(onClick = { navigator.popBackStack() }) {
                            Icon(
                                MiuixIcons.Back,
                                contentDescription = stringResource(Res.string.action_back)
                            )
                        }
                    },
                    scrollBehavior = topAppBarScrollBehavior
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .blurSource(topBarBackdrop)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .fillMaxHeight(),
            contentPadding = scaffoldContentPadding(
                paddingValues = paddingValues,
                bottomExtra = 12.dp
            ),
            overscrollEffect = null
        ) {
            item(key = "rules") {
                SmallTitle(text = stringResource(Res.string.non_lyrics_cleanup_rules_section))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ChipGrid(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        uiState.lyricsTagLineKeywords.forEach { rule ->
                            ManagedChip(
                                text = rule,
                                onClick = { editingRule = rule },
                                onDelete = { deletingRule = rule }
                            )
                        }
                    }
                    Text(
                        text = stringResource(Res.string.non_lyrics_cleanup_rules_hint),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            text = stringResource(Res.string.add_non_lyrics_cleanup_rule),
                            onClick = {
                                errorMessage = null
                                addingRule = true
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        TextButton(
                            text = stringResource(Res.string.reset_to_default),
                            onClick = viewModel::resetNonLyricsContentRules,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    CleanupRuleInputDialog(
        show = addingRule,
        title = stringResource(Res.string.add_non_lyrics_cleanup_rule),
        initialValue = "",
        errorMessage = errorMessage,
        onDismiss = {
            addingRule = false
            errorMessage = null
        },
        onSave = { value ->
            if (viewModel.addNonLyricsContentRule(value)) {
                addingRule = false
                errorMessage = null
            } else {
                errorMessage = invalidOrDuplicateRule
            }
        }
    )

    CleanupRuleInputDialog(
        show = editingRule != null,
        title = stringResource(Res.string.edit_non_lyrics_cleanup_rule),
        initialValue = editingRule.orEmpty(),
        errorMessage = errorMessage,
        onDismiss = {
            editingRule = null
            errorMessage = null
        },
        onSave = { value ->
            val oldRule = editingRule ?: return@CleanupRuleInputDialog
            if (viewModel.updateNonLyricsContentRule(oldRule, value)) {
                editingRule = null
                errorMessage = null
            } else {
                errorMessage = invalidOrDuplicateRule
            }
        }
    )

    WindowDialog(
        show = deletingRule != null,
        title = stringResource(Res.string.non_lyrics_cleanup_delete_rule_title),
        onDismissRequest = { deletingRule = null }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formattedStringResource(
                    Res.string.non_lyrics_cleanup_delete_rule_message,
                    deletingRule.orEmpty()
                ),
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = { deletingRule = null },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(Res.string.common_delete),
                    onClick = {
                        deletingRule?.let(viewModel::removeNonLyricsContentRule)
                        deletingRule = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

@Composable
private fun CleanupRuleInputDialog(
    show: Boolean,
    title: String,
    initialValue: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(show, initialValue) { mutableStateOf(initialValue) }

    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = text,
                onValueChange = { text = it },
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = errorMessage ?: stringResource(Res.string.non_lyrics_cleanup_rule_input_hint),
                color = if (errorMessage == null) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.error
                },
                fontSize = MiuixTheme.textStyles.footnote1.fontSize
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(Res.string.confirm),
                    onClick = { onSave(text) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}
