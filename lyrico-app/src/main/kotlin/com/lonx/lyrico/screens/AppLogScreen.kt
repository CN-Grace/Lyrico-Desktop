package com.lonx.lyrico.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.ui.components.blur.BlurredTopBar
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.action_copy_log
import com.lonx.lyrico.resources.action_delete
import com.lonx.lyrico.resources.action_export_logs
import com.lonx.lyrico.resources.app_log_delete_message
import com.lonx.lyrico.resources.app_log_delete_title
import com.lonx.lyrico.resources.app_log_detail_title
import com.lonx.lyrico.resources.app_log_empty
import com.lonx.lyrico.resources.app_log_field_detail
import com.lonx.lyrico.resources.app_log_field_level
import com.lonx.lyrico.resources.app_log_field_message
import com.lonx.lyrico.resources.app_log_field_related
import com.lonx.lyrico.resources.app_log_field_tag
import com.lonx.lyrico.resources.app_log_field_time
import com.lonx.lyrico.resources.app_log_field_type
import com.lonx.lyrico.resources.app_log_filter_all
import com.lonx.lyrico.resources.app_log_filter_level
import com.lonx.lyrico.resources.app_log_filter_type
import com.lonx.lyrico.resources.app_log_title
import com.lonx.lyrico.resources.cancel
import com.lonx.lyrico.resources.confirm
import com.lonx.lyrico.resources.log_retention_summary
import com.lonx.lyrico.resources.log_retention_title
import com.lonx.lyrico.resources.msg_copied_to_clipboard
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.log.LogRetentionOption
import com.lonx.lyrico.data.model.entity.AppLogEntity
import com.lonx.lyrico.platform.FileSavePicker
import com.lonx.lyrico.platform.rememberFileSavePicker
import com.lonx.lyrico.ui.components.scaffoldContentPadding
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.AppLogEvent
import com.lonx.lyrico.viewmodel.AppLogViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import java.awt.datatransfer.StringSelection
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// `ClipEntry` is marked @ExperimentalComposeUiApi in Compose Multiplatform; the marker is on the
// class, so the opt-in has to cover the whole screen rather than the single call.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppLogScreen(
    navigator: Navigator,
    // The OS save dialog is the one piece of this screen a test cannot drive, so it is injected; the
    // export itself (which files, which ids, what the user sees) stays real code under test.
    fileSavePicker: FileSavePicker? = null,
) {
    val viewModel: AppLogViewModel = koinViewModel()
    val picker =
        fileSavePicker ?: rememberFileSavePicker(title = stringResource(Res.string.action_export_logs))
    val logs by viewModel.logs.collectAsState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedLevel by remember { mutableStateOf<AppLogLevel?>(null) }
    var selectedType by remember { mutableStateOf<AppLogType?>(null) }
    var selectedLog by remember { mutableStateOf<AppLogEntity?>(null) }
    var selectedLogIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var pendingDeleteIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    val copiedMessage = stringResource(Res.string.msg_copied_to_clipboard)
    val logRetentionOption by viewModel.logRetentionOption.collectAsState()
    val logRetentionItems = LogRetentionOption.entries.map { stringResource(it.labelRes) }
    val selectedLogRetentionIndex = LogRetentionOption.entries.indexOf(logRetentionOption).coerceAtLeast(0)
    val filteredLogs = remember(logs, selectedLevel, selectedType) {
        logs.filter { log ->
            (selectedLevel == null || log.level == selectedLevel) &&
                    (selectedType == null || log.type == selectedType)
        }
    }

    val levelItems = remember {
        AppLogLevel.entries.map { it.labelRes }
    }
    val typeItems = remember {
        AppLogType.entries.map { it.labelRes }
    }
    val selectedLevelIndex = remember(selectedLevel) {
        selectedLevel?.let { level -> AppLogLevel.entries.indexOf(level) + 1 } ?: 0
    }
    val selectedTypeIndex = remember(selectedType) {
        selectedType?.let { type -> AppLogType.entries.indexOf(type) + 1 } ?: 0
    }

    LaunchedEffect(filteredLogs) {
        val visibleIds = filteredLogs.mapTo(mutableSetOf()) { it.id }
        selectedLogIds = selectedLogIds.intersect(visibleIds)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AppLogEvent.ShowMessage -> {
                    // `resolve()` (suspend), not `asString()` (composable): this runs inside a
                    // LaunchedEffect coroutine, which has no composition to resolve resources from.
                    event.message.resolve()?.let { snackbarHostState.showSnackbar(it) }
                }
            }
        }
    }

    val topBarBackdrop = rememberBarBlurBackdrop()
    Scaffold(
        topBar = {
            BlurredTopBar(backdrop = topBarBackdrop) {
                SmallTopAppBar(
                    color = Color.Transparent,
                    defaultWindowInsetsPadding = false,
                    title = stringResource(Res.string.app_log_title),
                    navigationIcon = {
                        IconButton(onClick = { navigator.popBackStack() }) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(Res.string.action_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = filteredLogs.isNotEmpty(),
                            onClick = {
                                scope.launch {
                                    // Android kept the pending ids in a `var` because the SAF launcher
                                    // returned asynchronously; here the coroutine waits for the dialog
                                    // itself, so the ids stay local and there is no state to stale out.
                                    val ids = filteredLogs.map { it.id }
                                    val target = picker.pick(
                                        "lyrico_log_${System.currentTimeMillis()}.log"
                                    ) ?: return@launch
                                    viewModel.exportLogs(target, ids)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Share,
                                contentDescription = stringResource(Res.string.action_export_logs)
                            )
                        }
                        IconButton(
                            enabled = filteredLogs.isNotEmpty(),
                            onClick = {
                                pendingDeleteIds = filteredLogs.map { it.id }
                                showDeleteConfirmDialog = true
                            }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = stringResource(Res.string.action_delete),
                                tint = MiuixTheme.colorScheme.error
                            )
                        }
                    },
                    scrollBehavior = topAppBarScrollBehavior
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .blurSource(topBarBackdrop)
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = scaffoldContentPadding(
                paddingValues = paddingValues,
                topExtra = 8.dp,
                bottomExtra = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item("filters") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    WindowDropdownPreference(
                        title = stringResource(Res.string.app_log_filter_level),
                        items = listOf(stringResource(Res.string.app_log_filter_all)) + levelItems.map {
                            stringResource(
                                it
                            )
                        },
                        selectedIndex = selectedLevelIndex,
                        onSelectedIndexChange = { index ->
                            selectedLevel = if (index == 0) null else AppLogLevel.entries[index - 1]
                        }
                    )
                    WindowDropdownPreference(
                        title = stringResource(Res.string.app_log_filter_type),
                        items = listOf(stringResource(Res.string.app_log_filter_all)) + typeItems.map {
                            stringResource(
                                it
                            )
                        },
                        selectedIndex = selectedTypeIndex,
                        onSelectedIndexChange = { index ->
                            selectedType = if (index == 0) null else AppLogType.entries[index - 1]
                        }
                    )
                    WindowDropdownPreference(
                        title = stringResource(Res.string.log_retention_title),
                        summary = stringResource(Res.string.log_retention_summary),
                        items = logRetentionItems,
                        selectedIndex = selectedLogRetentionIndex,
                        onSelectedIndexChange = { index ->
                            viewModel.setLogRetentionOption(LogRetentionOption.entries[index])
                        }
                    )
                }
            }

            if (filteredLogs.isEmpty()) {
                item("empty") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        BasicComponent(title = stringResource(Res.string.app_log_empty))
                    }
                }
            } else {
                items(filteredLogs, key = { it.id }) { log ->
                    AppLogItem(
                        log = log,
                        onClick = {
                            selectedLog = log
                            showDetailSheet = true
                        }
                    )
                }
            }
        }
    }

    AppLogDetailSheet(
        show = showDetailSheet,
        log = selectedLog,
        onDismiss = { showDetailSheet = false },
        onDismissFinished = {
            showDetailSheet = false
            selectedLog = null
        },
        onCopy = { log ->
            scope.launch {
                // The multiplatform Clipboard API takes a platform-native entry; on desktop that is an
                // AWT Transferable, which is also why `formatForCopy` returns the plain text. In a
                // headless environment Compose resolves `LocalClipboard` to its no-op implementation,
                // so this stays safe to call from a test.
                val clipEntry = ClipEntry(StringSelection(log.formatForCopy()))
                clipboardManager.setClipEntry(clipEntry)
                snackbarHostState.showSnackbar(copiedMessage)
            }
            showDetailSheet = false
        }
    )

    WindowDialog(
        show = showDeleteConfirmDialog,
        title = stringResource(Res.string.app_log_delete_title),
        onDismissRequest = { showDeleteConfirmDialog = false }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formattedStringResource(Res.string.app_log_delete_message, pendingDeleteIds.size),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = { showDeleteConfirmDialog = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = stringResource(Res.string.confirm),
                    onClick = {
                        viewModel.deleteLogs(pendingDeleteIds)
                        selectedLogIds = emptySet()
                        pendingDeleteIds = emptyList()
                        showDeleteConfirmDialog = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}


@Composable
private fun AppLogItem(
    log: AppLogEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
    ) {
        BasicComponent(
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            onClick = onClick
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SeverityBadge(log.level)
                    Text(
                        text = stringResource(log.type.labelRes),
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                Text(
                    style = MiuixTheme.textStyles.body2,
                    text = formatTime(log.createdAt),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = log.message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = log.tag,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            log.relatedId?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SeverityBadge(level: AppLogLevel) {
    val background = when (level) {
        AppLogLevel.ERROR -> MiuixTheme.colorScheme.error
        AppLogLevel.WARNING -> MiuixTheme.colorScheme.tertiaryContainer
        AppLogLevel.INFO -> MiuixTheme.colorScheme.primary
        AppLogLevel.DEBUG -> MiuixTheme.colorScheme.secondaryContainer
    }
    val content = when (level) {
        AppLogLevel.ERROR -> MiuixTheme.colorScheme.onError
        AppLogLevel.WARNING -> MiuixTheme.colorScheme.onSurface
        AppLogLevel.INFO -> MiuixTheme.colorScheme.onPrimary
        AppLogLevel.DEBUG -> MiuixTheme.colorScheme.onSurfaceVariantActions
    }
    Text(
        text = stringResource(level.labelRes),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = content,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}

@Composable
private fun AppLogDetailSheet(
    show: Boolean,
    log: AppLogEntity?,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onCopy: (AppLogEntity) -> Unit
) {
    WindowBottomSheet(
        show = show,
        enableNestedScroll = false,
        title = stringResource(Res.string.app_log_detail_title),
        endAction = {
            log?.let {
                IconButton(
                    onClick = {
                        onCopy(it)
                    }
                ) {
                    Icon(
                        imageVector = MiuixIcons.Copy,
                        contentDescription = stringResource(Res.string.action_copy_log)
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished
    ) {
        log ?: return@WindowBottomSheet
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Column {
                DetailRow(
                    stringResource(Res.string.app_log_field_time),
                    formatDateTime(log.createdAt)
                )
                DetailRow(
                    stringResource(Res.string.app_log_field_level),
                    stringResource(log.level.labelRes)
                )
                DetailRow(
                    stringResource(Res.string.app_log_field_type),
                    stringResource(log.type.labelRes)
                )
                DetailRow(stringResource(Res.string.app_log_field_tag), log.tag)
                log.relatedId?.let {
                    DetailRow(stringResource(Res.string.app_log_field_related), it)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(Res.string.app_log_field_message),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontWeight = FontWeight.Bold
                )
                SelectionContainer {
                    Text(
                        text = log.message,
                        modifier = Modifier.padding(top = 4.dp),
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                log.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(Res.string.app_log_field_detail),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontWeight = FontWeight.Bold
                    )
                    SelectionContainer {
                        Text(
                            text = detail,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            color = MiuixTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontWeight = FontWeight.Bold
        )
        SelectionContainer {
            Text(
                text = value,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


private fun AppLogEntity.formatForCopy(): String = buildString {
    appendLine("${formatDateTime(createdAt)} [$level/$type] $tag")
    appendLine(message)
    relatedId?.let { appendLine("Related: $it") }
    detail?.takeIf { it.isNotBlank() }?.let {
        appendLine()
        appendLine(it)
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun formatDateTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (contains(id)) this - id else this + id
