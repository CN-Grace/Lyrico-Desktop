package com.lonx.lyrico.ui.components.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lonx.lyrico.data.model.entity.AlbumEntity
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.song_count
import com.lonx.lyrico.ui.components.cover.CoverImage
import com.lonx.lyrico.utils.formattedStringResource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一位艺人名下的专辑行：封面、专辑名、`专辑艺术家 · N 首 · 年份` 摘要。
 *
 * 与专辑网格里的卡片是两套呈现：网格按封面铺开，这里是一行文字为主。`trailingContent` 给调用方
 * 放右侧的图标按钮（艺人详情页放「更多」），不传就只是一个可点的行。
 *
 * 搬到桌面时删掉了三样 Android 才有的东西，其余照旧：
 *
 * * `LocalView` + `HapticFeedbackConstants` 的震动反馈和 `detectHorizontalDragGestures` 的
 *   横向拖拽——这一段在 Android 上就没有任何效果（没有状态、没有副作用），是早先改动的残留；
 * * `@SuppressLint("DefaultLocale")`，它防的是 `String.format` 跟随系统区域把数字写成
 *   阿拉伯文数字；这里走的是 compose-resources 的 `formattedStringResource`，
 *   与系统默认 `Locale` 无关；
 * * `stringResource(R.string.song_count, ...)` 带格式参数的重载，改成
 *   [formattedStringResource]，见 `FormattedStringResource` 的说明。
 */
@Composable
fun AlbumListItem(
    album: AlbumEntity,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .clickable(
                onClick = {
                    onClick()
                }
            )
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            CoverImage(
                uri = album.coverSongUri,
                lastModified = album.coverSongLastModified,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(6.dp)
            )


            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = album.name,
                    maxLines = 1,
                    fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!album.albumArtist.isNullOrBlank()) {
                        Text(
                            text = "${album.albumArtist} · ",
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            fontSize = 13.sp,
                            maxLines = 1,
                            fontWeight = FontWeight.Bold,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Text(
                        text = formattedStringResource(Res.string.song_count, album.songCount),
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!album.year.isNullOrBlank()) {
                        Text(
                            text = " · ${album.year}",
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            fontSize = 13.sp,
                            maxLines = 1,
                            fontWeight = FontWeight.Bold,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            trailingContent?.let {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    trailingContent()
                }
            }
        }
    }
}
