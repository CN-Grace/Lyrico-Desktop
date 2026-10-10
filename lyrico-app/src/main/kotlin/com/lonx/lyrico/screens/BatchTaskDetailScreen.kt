package com.lonx.lyrico.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.action_close
import com.lonx.lyrico.resources.batch_match_duration_format
import com.lonx.lyrico.resources.batch_match_stat_format
import com.lonx.lyrico.resources.batch_task_detail_no_records
import com.lonx.lyrico.resources.batch_task_detail_progress
import com.lonx.lyrico.resources.batch_task_detail_title
import com.lonx.lyrico.resources.batch_task_status_failed
import com.lonx.lyrico.resources.batch_task_status_skipped
import com.lonx.lyrico.resources.batch_task_status_succeeded
import com.lonx.lyrico.ui.components.scaffoldTopHorizontalPadding
import com.lonx.lyrico.ui.navigation.EditMetadataDestination
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.BatchTaskDetailViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

enum class TaskDetailTab(val labelRes: StringResource) {
    SUCCEEDED(Res.string.batch_task_status_succeeded),
    FAILED(Res.string.batch_task_status_failed),
    SKIPPED(Res.string.batch_task_status_skipped)
}

@Composable
fun BatchTaskDetailScreen(
    taskId: String,
    navigator: Navigator
) {
    // Android resolved the argument through the generated destination the same way; on desktop the
    // parameter is read in `LyricoNavHost` and passed in, so the view model gets it identically.
    val viewModel: BatchTaskDetailViewModel = koinViewModel(parameters = { parametersOf(taskId) })
    val task by viewModel.task.collectAsState()
    val items by viewModel.items.collectAsState()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()

    val tabs = remember { TaskDetailTab.entries }
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(Res.string.batch_task_detail_title),
                navigationIcon = {
                    IconButton(
                        onClick = { navigator.popBackStack() }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                actions = {
                    val t = task
                    if (t != null && (t.status == BatchTaskStatus.RUNNING || t.status == BatchTaskStatus.QUEUED)) {
                        IconButton(
                            onClick = { viewModel.cancelTask() }
                        ) {
                            Icon(
                                // `android.R.drawable.ic_menu_close_clear_cancel` has no desktop
                                // equivalent; Miuix's `Close` is the X the rest of the port uses.
                                imageVector = MiuixIcons.Close,
                                contentDescription = stringResource(Res.string.action_close),
                                tint = MiuixTheme.colorScheme.error
                            )
                        }
                    }
                },
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldTopHorizontalPadding(paddingValues))
        ) {
            val t = task
            if (t != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    val typeLabel = stringResource(t.type.labelRes)
                    val statusLabel = stringResource(t.status.labelRes)
                    val startedAt = t.startedAt
                    val finishedAt = t.finishedAt
                    val durationSecs = if (startedAt != null && finishedAt != null) {
                        (finishedAt - startedAt) / 1000.0
                    } else null
                    
                    val summary = buildString {
                        // 第一行：任务类型 · 状态
                        append("$typeLabel · $statusLabel")
                        
                        // 第二行：统计信息
                        append("\n")
                        append(
                            formattedStringResource(
                                Res.string.batch_match_stat_format,
                                t.successCount,
                                t.failureCount,
                                t.skippedCount
                            )
                        )
                        
                        // 第三行：耗时（如果有）
                        if (durationSecs != null) {
                            append("\n")
                            append(
                                formattedStringResource(
                                    Res.string.batch_match_duration_format,
                                    durationSecs
                                )
                            )
                        }
                    }
                    
                    BasicComponent(
                        title = formattedStringResource(
                            Res.string.batch_task_detail_progress,
                            t.current,
                            t.total
                        ),
                        summary = summary
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                TabRowWithContour(
                    tabs = tabs.map { stringResource(it.labelRes) },
                    selectedTabIndex = pagerState.currentPage,
                    onTabSelected = { index ->
                        scope.launch { pagerState.animateScrollToPage(index) }
                    }
                )
            }

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
                key = { index -> tabs[index].name }
            ) { pageIndex ->
                val currentTab = tabs[pageIndex]
                val filteredItems = remember(items, currentTab) {
                    when (currentTab) {
                        TaskDetailTab.SUCCEEDED -> items.filter { it.status == BatchTaskStatus.SUCCEEDED }
                        TaskDetailTab.FAILED -> items.filter { it.status == BatchTaskStatus.FAILED }
                        TaskDetailTab.SKIPPED -> items.filter { it.status == BatchTaskStatus.SKIPPED }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    overscrollEffect = null,
                ) {
                    if (filteredItems.isEmpty()) {
                        item {
                            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                                BasicComponent(title = stringResource(Res.string.batch_task_detail_no_records))
                            }
                        }
                    } else {
                        items(
                            items = filteredItems,
                            key = { it.itemId }
                        ) { item ->
                            BatchTaskItemCard(
                                item = item,
                                onClick = {
                                    navigator.navigate(EditMetadataDestination(item.songUri))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchTaskItemCard(
    item: BatchTaskItemEntity,
    onClick: () -> Unit
) {
    val summary = buildString {
        append(item.filePath ?: item.songUri)
        if (item.errorMessage != null) {
            append("\n")
            append(item.errorMessage)
        }
    }
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        ArrowPreference(
            title = item.fileName,
            summary = summary,
            onClick = onClick
        )
    }
}
