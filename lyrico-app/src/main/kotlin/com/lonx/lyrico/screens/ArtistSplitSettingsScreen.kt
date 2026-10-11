package com.lonx.lyrico.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.ui.components.blur.BlurredTopBar
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.add_custom_separator
import com.lonx.lyrico.resources.add_no_split_artist
import com.lonx.lyrico.resources.artist_index_current
import com.lonx.lyrico.resources.artist_index_rebuild_complete
import com.lonx.lyrico.resources.artist_index_rebuild_failed
import com.lonx.lyrico.resources.artist_index_rebuild_pending
import com.lonx.lyrico.resources.artist_index_rebuilding
import com.lonx.lyrico.resources.artist_name_input_hint
import com.lonx.lyrico.resources.artist_split_delete_rule_message
import com.lonx.lyrico.resources.artist_split_delete_rule_title
import com.lonx.lyrico.resources.artist_split_enabled
import com.lonx.lyrico.resources.artist_split_enabled_summary
import com.lonx.lyrico.resources.artist_split_general_section
import com.lonx.lyrico.resources.artist_split_settings_title
import com.lonx.lyrico.resources.cancel
import com.lonx.lyrico.resources.common_delete
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.duplicate_builtin_item
import com.lonx.lyrico.resources.duplicate_item
import com.lonx.lyrico.resources.edit_custom_separator
import com.lonx.lyrico.resources.edit_no_split_artist
import com.lonx.lyrico.resources.invalid_empty_input
import com.lonx.lyrico.resources.rebuild_artist_index
import com.lonx.lyrico.resources.rebuild_artist_index_now
import com.lonx.lyrico.resources.reset_to_default
import com.lonx.lyrico.resources.section_artist_separators
import com.lonx.lyrico.resources.section_no_split_artists
import com.lonx.lyrico.resources.separator_input_hint
import com.lonx.lyrico.data.model.artist.ArtistSplitDefaults
import com.lonx.lyrico.data.model.artist.CustomArtistSeparator
import com.lonx.lyrico.data.model.artist.CustomNoSplitArtist
import com.lonx.lyrico.ui.components.scaffoldContentPadding
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.viewmodel.ArtistSplitSettingsViewModel
import com.lonx.lyrico.viewmodel.ArtistSplitValidationError
import com.lonx.lyrico.utils.formattedStringResource
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ArtistSplitSettingsScreen(
    navigator: Navigator
) {
    val viewModel: ArtistSplitSettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var editingSeparator by remember { mutableStateOf<CustomArtistSeparator?>(null) }
    var addingSeparator by remember { mutableStateOf(false) }
    var editingNoSplitArtist by remember { mutableStateOf<CustomNoSplitArtist?>(null) }
    var addingNoSplitArtist by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingRuleDelete?>(null) }

    // Android 的两条 Toast 换成 Miuix 的 Snackbar：文案仍然在展示这一刻解析（语言切换后也跟着换），
    // 状态清理的时机不变（提示发出后再清）。
    val snackbarHostState = remember { SnackbarHostState() }
    uiState.error?.let { error ->
        val message = when (error) {
            ArtistSplitValidationError.EMPTY -> stringResource(Res.string.invalid_empty_input)
            ArtistSplitValidationError.DUPLICATE -> stringResource(Res.string.duplicate_item)
            ArtistSplitValidationError.DUPLICATE_BUILTIN -> stringResource(Res.string.duplicate_builtin_item)
            ArtistSplitValidationError.REBUILD_FAILED -> stringResource(Res.string.artist_index_rebuild_failed)
        }
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    if (uiState.rebuildCompleted) {
        val message = stringResource(Res.string.artist_index_rebuild_complete)
        LaunchedEffect(Unit) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearRebuildCompleted()
        }
    }

    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val topBarBackdrop = rememberBarBlurBackdrop()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BlurredTopBar(backdrop = topBarBackdrop) {
                SmallTopAppBar(
                    color = Color.Transparent,
                    defaultWindowInsetsPadding = false,
                    title = stringResource(Res.string.artist_split_settings_title),
                    navigationIcon = {
                        IconButton(onClick = { navigator.popBackStack() }) {
                            Icon(MiuixIcons.Back, contentDescription = stringResource(Res.string.action_back))
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
            item(key = "config") {
                SmallTitle(text = stringResource(Res.string.artist_split_general_section))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = stringResource(Res.string.artist_split_enabled),
                        summary = stringResource(Res.string.artist_split_enabled_summary),
                        checked = uiState.config.enabled,
                        onCheckedChange = viewModel::setEnabled
                    )
                    ArrowPreference(
                        title = stringResource(
                            if (uiState.hasPendingIndexRebuild) {
                                Res.string.rebuild_artist_index_now
                            } else {
                                Res.string.rebuild_artist_index
                            }
                        ),
                        onClick = viewModel::rebuildArtistIndex,
                        summary = when {
                            uiState.isRebuildingIndex -> stringResource(Res.string.artist_index_rebuilding)
                            uiState.hasPendingIndexRebuild -> stringResource(Res.string.artist_index_rebuild_pending)
                            else -> stringResource(Res.string.artist_index_current)
                        }
                    )
                }
            }

            item(key = "separators") {
                SmallTitle(text = stringResource(Res.string.section_artist_separators))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    val separatorChips = ArtistSplitDefaults.BUILTIN_SEPARATORS
                        .filterNot { it.id in uiState.config.hiddenBuiltinSeparatorIds }
                        .map { separator ->
                            ArtistSplitChipItem(
                                id = separator.id,
                                text = separator.displayName,
                                checked = uiState.config.builtinSeparatorOverrides[separator.id]
                                    ?: separator.defaultEnabled,
                                isBuiltin = true
                            )
                        } + uiState.config.customSeparators.map { separator ->
                            ArtistSplitChipItem(
                                id = separator.id,
                                text = separator.value,
                                checked = separator.enabled,
                                isBuiltin = false
                            )
                        }

                    ChipGrid(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        separatorChips.forEach { chip ->
                            RuleChip(
                                text = chip.text,
                                checked = chip.checked,
                                onCheckedChange = {
                                    if (chip.isBuiltin) {
                                        viewModel.setBuiltinSeparatorEnabled(chip.id, it)
                                    } else {
                                        viewModel.setCustomSeparatorEnabled(chip.id, it)
                                    }
                                },
                                onDelete = {
                                    pendingDelete = PendingRuleDelete.Separator(chip.id, chip.text, chip.isBuiltin)
                                }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            text = stringResource(Res.string.add_custom_separator),
                            onClick = { addingSeparator = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary()
                        )
                        TextButton(
                            text = stringResource(Res.string.reset_to_default),
                            onClick = viewModel::resetSeparators,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item(key = "custom_no_split") {
                SmallTitle(text = stringResource(Res.string.section_no_split_artists))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    if (uiState.config.customNoSplitArtists.isNotEmpty()) {
                        ChipGrid(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            uiState.config.customNoSplitArtists.forEach { artist ->
                                RuleChip(
                                    text = artist.name,
                                    checked = artist.enabled,
                                    onCheckedChange = {
                                        viewModel.setCustomNoSplitArtistEnabled(artist.id, it)
                                    },
                                    onDelete = {
                                        pendingDelete =
                                            PendingRuleDelete.NoSplitArtist(artist.id, artist.name)
                                    }
                                )
                            }
                        }
                    }
                    ArrowPreference(
                        title = stringResource(Res.string.add_no_split_artist),
                        onClick = { addingNoSplitArtist = true }
                    )
                }
            }
        }
    }

    ArtistSplitInputDialog(
        show = addingSeparator,
        title = stringResource(Res.string.add_custom_separator),
        initialValue = "",
        hint = stringResource(Res.string.separator_input_hint),
        showDelete = false,
        onDismiss = { addingSeparator = false },
        onSave = {
            if (viewModel.addCustomSeparator(it)) addingSeparator = false
        }
    )

    ArtistSplitInputDialog(
        show = editingSeparator != null,
        title = stringResource(Res.string.edit_custom_separator),
        initialValue = editingSeparator?.value.orEmpty(),
        hint = stringResource(Res.string.separator_input_hint),
        showDelete = true,
        onDismiss = { editingSeparator = null },
        onDelete = {
            editingSeparator?.let { viewModel.removeCustomSeparator(it.id) }
            editingSeparator = null
        },
        onSave = { value ->
            val separator = editingSeparator ?: return@ArtistSplitInputDialog
            if (viewModel.updateCustomSeparator(separator.id, value)) editingSeparator = null
        }
    )

    ArtistSplitInputDialog(
        show = addingNoSplitArtist,
        title = stringResource(Res.string.add_no_split_artist),
        initialValue = "",
        hint = stringResource(Res.string.artist_name_input_hint),
        showDelete = false,
        onDismiss = { addingNoSplitArtist = false },
        onSave = {
            if (viewModel.addCustomNoSplitArtist(it)) addingNoSplitArtist = false
        }
    )

    ArtistSplitInputDialog(
        show = editingNoSplitArtist != null,
        title = stringResource(Res.string.edit_no_split_artist),
        initialValue = editingNoSplitArtist?.name.orEmpty(),
        hint = stringResource(Res.string.artist_name_input_hint),
        showDelete = true,
        onDismiss = { editingNoSplitArtist = null },
        onDelete = {
            editingNoSplitArtist?.let { viewModel.removeCustomNoSplitArtist(it.id) }
            editingNoSplitArtist = null
        },
        onSave = { name ->
            val artist = editingNoSplitArtist ?: return@ArtistSplitInputDialog
            if (viewModel.updateCustomNoSplitArtist(artist.id, name)) editingNoSplitArtist = null
        }
    )

    ConfirmRuleDeleteDialog(
        pendingDelete = pendingDelete,
        onDismiss = { pendingDelete = null },
        onConfirm = { delete ->
            when (delete) {
                is PendingRuleDelete.Separator -> {
                    if (delete.isBuiltin) {
                        viewModel.removeBuiltinSeparator(delete.id)
                    } else {
                        viewModel.removeCustomSeparator(delete.id)
                    }
                }
                is PendingRuleDelete.NoSplitArtist -> {
                    viewModel.removeCustomNoSplitArtist(delete.id)
                }
            }
            pendingDelete = null
        }
    )
}

private data class ArtistSplitChipItem(
    val id: String,
    val text: String,
    val checked: Boolean,
    val isBuiltin: Boolean
)

private sealed interface PendingRuleDelete {
    val id: String
    val label: String

    data class Separator(
        override val id: String,
        override val label: String,
        val isBuiltin: Boolean
    ) : PendingRuleDelete

    data class NoSplitArtist(
        override val id: String,
        override val label: String
    ) : PendingRuleDelete
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGrid(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@Composable
private fun RuleChip(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val background = if (checked) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MiuixTheme.colorScheme.secondaryContainer
    }
    val borderColor = if (checked) {
        MiuixTheme.colorScheme.primary.copy(alpha = 0.48f)
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.16f)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable { onCheckedChange(!checked) }
            .padding(start = 8.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                    if (checked) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.16f)
                    }
                )
                .clickable { onCheckedChange(!checked) },
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    color = MiuixTheme.colorScheme.onPrimary,
                    fontSize = MiuixTheme.textStyles.footnote1.fontSize
                )
            }
        }
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp),
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = MiuixIcons.Delete,
            contentDescription = stringResource(Res.string.common_delete),
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions
        )
    }
}

@Composable
private fun ArtistSplitInputDialog(
    show: Boolean,
    title: String,
    initialValue: String,
    hint: String,
    showDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit = {}
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
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = hint,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = MiuixTheme.textStyles.footnote1.fontSize
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                if (showDelete) {
                    TextButton(
                        text = stringResource(Res.string.common_delete),
                        onClick = onDelete,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                }
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

@Composable
private fun ConfirmRuleDeleteDialog(
    pendingDelete: PendingRuleDelete?,
    onDismiss: () -> Unit,
    onConfirm: (PendingRuleDelete) -> Unit
) {
    WindowDialog(
        show = pendingDelete != null,
        title = stringResource(Res.string.artist_split_delete_rule_title),
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formattedStringResource(
                    Res.string.artist_split_delete_rule_message,
                    pendingDelete?.label.orEmpty()
                ),
                color = MiuixTheme.colorScheme.onSurface
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
                    text = stringResource(Res.string.common_delete),
                    onClick = {
                        pendingDelete?.let(onConfirm)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}
