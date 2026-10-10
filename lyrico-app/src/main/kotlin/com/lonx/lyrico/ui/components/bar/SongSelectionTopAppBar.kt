package com.lonx.lyrico.ui.components.bar

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.resources.action_close
import com.lonx.lyrico.resources.action_deselect_all
import com.lonx.lyrico.resources.action_select_all
import com.lonx.lyrico.resources.selection_mode_selected_count
import com.lonx.lyrico.ui.components.scaffoldTopAppBarInsetsPadding
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * The bar that replaces the normal one while songs are selected: how many are selected, select-all /
 * deselect-all, and close.
 *
 * This used to live in `ui/components/bar/SongSelectionSupport.kt` together with
 * `SongBatchSelectionActions`, the twelve-action expandable FAB. That file is **left in the Android
 * tree** and this one is named differently on purpose: the FAB needs four batch view models
 * (match / ReplayGain / lyrics format / export) plus eight batch sheets, and two of those depend on
 * decisions that are still open (the ReplayGain decoder, the QuickJS plugin runtime). Splitting the
 * bar out keeps the portable half from waiting on them, and the different file name keeps
 * `scripts/port-frontier.py` from reporting a stale duplicate.
 *
 * Only the bar itself is changed on the way over: `Color.Unspecified` stayed the default so the host
 * screen can pass the blur backdrop's colour, and the compact-window rule (hide select-all below
 * 360 dp, where three labels cannot fit) is unchanged.
 */
@Composable
fun SongSelectionTopAppBar(
    songs: List<SongEntity>,
    selectedSongUris: Set<String>,
    scrollBehavior: ScrollBehavior,
    color: Color = Color.Unspecified,
    applyInsets: Boolean = true,
    onSelectAll: (List<SongEntity>) -> Unit,
    onDeselectAll: () -> Unit,
    onClose: () -> Unit
) {
    val allSelected = songs.isNotEmpty() && selectedSongUris.containsAll(songs.map { it.uri })

    BoxWithConstraints {
        val compactTopBar = maxWidth < 360.dp

        SmallTopAppBar(
            title = "",
            color = color,
            modifier = if (applyInsets) {
                Modifier.scaffoldTopAppBarInsetsPadding()
            } else {
                Modifier
            },
            scrollBehavior = scrollBehavior,
            defaultWindowInsetsPadding = false,
            navigationIcon = {
                Text(
                    text = formattedStringResource(
                        Res.string.selection_mode_selected_count,
                        selectedSongUris.size
                    )
                )
            },
            actions = {
                if (!compactTopBar) {
                    TextButton(
                        text = stringResource(
                            if (allSelected) {
                                Res.string.action_deselect_all
                            } else {
                                Res.string.action_select_all
                            }
                        ),
                        onClick = {
                            if (allSelected) {
                                onDeselectAll()
                            } else {
                                onSelectAll(songs)
                            }
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }

                TextButton(
                    text = stringResource(Res.string.action_close),
                    onClick = onClose,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        )
    }
}
