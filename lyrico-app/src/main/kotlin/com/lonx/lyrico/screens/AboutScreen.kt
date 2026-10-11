package com.lonx.lyrico.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.lonx.lyrico.BuildInfo
import com.lonx.lyrico.ui.components.blur.BlurredTopBar
import com.lonx.lyrico.ui.components.blur.blurSource
import com.lonx.lyrico.ui.components.blur.rememberBarBlurBackdrop
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.about_app_info
import com.lonx.lyrico.resources.about_app_version
import com.lonx.lyrico.resources.about_auto_check_update
import com.lonx.lyrico.resources.about_auto_check_update_sub
import com.lonx.lyrico.resources.about_check_update
import com.lonx.lyrico.resources.about_check_update_default
import com.lonx.lyrico.resources.about_contribution_count
import com.lonx.lyrico.resources.about_contributors
import com.lonx.lyrico.resources.about_no_contributors
import com.lonx.lyrico.resources.about_project_url
import com.lonx.lyrico.resources.about_project_url_sub
import com.lonx.lyrico.resources.about_title
import com.lonx.lyrico.resources.action_back
import com.lonx.lyrico.resources.load_failed
import com.lonx.lyrico.resources.title_opensource_licence
import com.lonx.lyrico.ui.components.scaffoldContentPadding
import com.lonx.lyrico.utils.UpdateEffect
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.viewmodel.AboutViewModel
import com.lonx.lyrico.viewmodel.UiError
import com.lonx.lyrico.ui.navigation.Navigator
import com.lonx.lyrico.ui.navigation.OpensourceLicenceDestination
import org.koin.compose.viewmodel.koinViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** Android kept this in `App.Companion`; the desktop shell has no such object, and this page is its only user. */
private const val TELEGRAM_GROUP_LINK = "https://t.me/lyrico_app"

@Composable
fun AboutScreen(
    navigator: Navigator
) {
    val viewModel: AboutViewModel = koinViewModel()
    val checkUpdateEnabled by viewModel.checkUpdateEnabled.collectAsStateWithLifecycle()
    val updateEffect by viewModel.updateEffect.collectAsStateWithLifecycle(
        initialValue = UpdateEffect(Res.string.about_check_update_default)
    )
    val contributors by viewModel.contributors.collectAsStateWithLifecycle()
    val loading by viewModel.loadingContributors.collectAsStateWithLifecycle()
    val error by viewModel.contributorsError.collectAsStateWithLifecycle()

    val errorText = when (val e = error) {
        null -> null
        UiError.LoadFailed -> stringResource(Res.string.load_failed)
        is UiError.Message -> e.text
    }
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val topBarBackdrop = rememberBarBlurBackdrop()
    Scaffold(
        topBar = {
            BlurredTopBar(backdrop = topBarBackdrop) {
                SmallTopAppBar(
                    color = Color.Transparent,
                    defaultWindowInsetsPadding = false,
                    title = stringResource(Res.string.about_title),
                    navigationIcon = {
                        IconButton(
                            onClick = { navigator.popBackStack() }
                        ) {
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
            overscrollEffect = null,
        ) {
            item {
                SmallTitle(text = stringResource(Res.string.about_app_info))
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    BasicComponent(
                        title = stringResource(Res.string.about_app_version),
                        summary = "${BuildInfo.VERSION_NAME}(${BuildInfo.VERSION_CODE})"
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.about_project_url),
                        summary = stringResource(Res.string.about_project_url_sub),
                        endActions = {
                            Text(
                                text = "GitHub",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        },
                        onClick = {
                            viewModel.openBrowser(
                                "https://github.com/${BuildInfo.UPDATE_OWNER}/${BuildInfo.UPDATE_REPO}"
                            )
                        }
                    )
                    ArrowPreference(
                        title = "Telegram",
                        summary = TELEGRAM_GROUP_LINK,
                        endActions = {
                            Text(
                                text = "Telegram",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        },
                        onClick = {
                            viewModel.openBrowser(TELEGRAM_GROUP_LINK)
                        }
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.title_opensource_licence),
                        onClick = {
                            navigator.navigate(OpensourceLicenceDestination)
                        }
                    )
                    SwitchPreference(
                        title = stringResource(Res.string.about_auto_check_update),
                        summary = stringResource(Res.string.about_auto_check_update_sub),
                        checked = checkUpdateEnabled,
                        onCheckedChange = viewModel::setCheckUpdateEnabled
                    )
                    ArrowPreference(
                        title = stringResource(Res.string.about_check_update),
                        summary = formattedStringResource(
                            updateEffect.messageRes,
                            *updateEffect.formatArgs.toTypedArray()
                        ),
                        onClick = viewModel::checkUpdate
                    )
                }
            }

            item {
                SmallTitle(text = stringResource(Res.string.about_contributors))
            }

            when {
                loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(size = 32.dp)
                        }
                    }
                }

                errorText != null -> {
                    item {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .fillMaxWidth()
                        ) {
                            BasicComponent(
                                onClick = {
                                    viewModel.loadContributors()
                                }
                            ) {
                                Text(
                                    text = errorText,
                                    modifier = Modifier.padding(12.dp),
                                    color = MiuixTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                contributors.isEmpty() -> {
                    item {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .fillMaxWidth()
                        ) {
                            BasicComponent(
                                title = stringResource(Res.string.about_no_contributors)
                            )
                        }
                    }
                }

                else -> {
                    itemsIndexed(
                        items = contributors,
                        key = { _, contributor -> contributor.id }
                    ) { index, contributor ->
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(top = if (index == 0) 0.dp else 12.dp)
                        ) {
                            ArrowPreference(
                                title = contributor.login,
                                summary = formattedStringResource(
                                    Res.string.about_contribution_count,
                                    contributor.contributions
                                ),
                                startAction = {
                                    AsyncImage(
                                        model = contributor.avatar_url,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(end = 16.dp)
                                            .size(36.dp)
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                },
                                endActions = {
                                    Text(
                                        text = "GitHub",
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                                    )
                                },
                                onClick = {
                                    viewModel.openBrowser(contributor.html_url)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 安卓把更新对话框挂在 Activity 外壳上（那样它能在任何页面之上弹出）；桌面端的关于页是触发检查、
    // 也是展示结果的地方，所以这一页自己兼。`PLAN.md` 里记了两者的差别。
}
