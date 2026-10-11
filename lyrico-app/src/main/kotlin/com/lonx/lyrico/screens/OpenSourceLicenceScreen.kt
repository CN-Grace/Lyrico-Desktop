package com.lonx.lyrico.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.data.model.AppLicences
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.title_opensource_licence
import com.lonx.lyrico.ui.components.library.LibraryScrollbar
import com.lonx.lyrico.ui.components.library.libraryScrollbarOverlay
import com.lonx.lyrico.ui.components.scaffoldContentPadding
import com.lonx.lyrico.ui.navigation.Navigator
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 开源许可列表。
 *
 * 安卓版的数据是 `aboutlibraries` 插件在编译期从依赖图里生成的（`R.raw.aboutlibraries`）；桌面构建
 * 没有那个插件，所以这一页渲染的是手维护的 [AppLicences.all] —— 代价与保障写在它的 KDoc 里。
 * 滚动条用端口自己的 [LibraryScrollbar]（`my.nanihadesuka` 是安卓专用库）。
 */
@Composable
fun OpenSourceLicenceScreen(
    navigator: Navigator
) {
    val libraries = AppLicences.all
    val listState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(Res.string.title_opensource_licence),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            navigator.popBackStack()
                        }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null
                        )
                    }
                },
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                    .fillMaxHeight()
                    .padding(
                        scaffoldContentPadding(
                            paddingValues = paddingValues,
                            bottomExtra = 12.dp
                        )
                    ),
                overscrollEffect = null,
            ) {
                items(libraries, key = { it.id }) { library ->
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp),
                    ) {
                        ArrowPreference(
                            title = library.name,
                            summary = buildString {
                                append(library.version)
                                if (library.licences.isNotEmpty()) {
                                    append("\n")
                                    append(library.licences.joinToString(", "))
                                }
                            },
                            onClick = {
                                library.url?.let { uriHandler.openUri(it) }
                            }
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // 端口自己的滚动条是覆盖层（`libraryScrollbarOverlay` 负责让开顶栏/底栏），所以它是
            // `LazyColumn` 的兄弟节点而不是包装器 —— 见 `LibraryScrollbar` 的 KDoc。
            LibraryScrollbar(
                state = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .libraryScrollbarOverlay(
                        paddingValues = paddingValues,
                        extraTop = 12.dp,
                        extraBottom = 12.dp,
                    ),
            )
        }
    }
}
