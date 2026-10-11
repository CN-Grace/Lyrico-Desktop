package com.lonx.lyrico.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页码圆点指示器：提示可左右滑动的分页区域，当前页的圆点更大且不透明。
 *
 * 默认配色沿用 Accompanist HorizontalPagerIndicator 的思路：高亮用当前内容色
 * （`onSurface`，浅色主题接近黑、深色主题接近白），非当前页用同色降透明度，
 * 不使用主题强调色，避免与封面区域的中性文字色打架。
 *
 * 只有一页时不显示；传入 [onPageClick] 后点击圆点可直接切到对应页。
 *
 * @param pageCount 总页数
 * @param currentPage 当前页下标
 * @param spacing 圆点间距
 * @param pageDescriptions 各页的无障碍描述，缺省时不设置
 * @param onPageClick 点击第 index 个圆点的回调，为 null 时圆点不可点击
 */
@Composable
fun PagerDotsIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    activeColor: Color = MiuixTheme.colorScheme.onSurface,
    inactiveColor: Color = activeColor.copy(alpha = 0.4f),
    activeSize: Dp = 8.dp,
    inactiveSize: Dp = 5.dp,
    spacing: Dp = 6.dp,
    pageDescriptions: List<String> = emptyList(),
    onPageClick: ((Int) -> Unit)? = null
) {
    if (pageCount <= 1) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            val dotSize by animateDpAsState(
                targetValue = if (selected) activeSize else inactiveSize,
                label = "PagerDotSize"
            )
            val dotColor by animateColorAsState(
                targetValue = if (selected) activeColor else inactiveColor,
                label = "PagerDotColor"
            )
            val description = pageDescriptions.getOrNull(index)

            // 外层固定为最大圆点尺寸，圆点放大时整行不会跳动；
            // 在 clickable 之前 clip 成圆形，点击涟漪才与圆点形状一致
            Box(
                modifier = Modifier
                    .size(activeSize)
                    .clip(CircleShape)
                    .semantics {
                        if (description != null) contentDescription = description
                    }
                    .clickable(enabled = !selected && onPageClick != null) {
                        onPageClick?.invoke(index)
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(dotSize)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }
    }
}
