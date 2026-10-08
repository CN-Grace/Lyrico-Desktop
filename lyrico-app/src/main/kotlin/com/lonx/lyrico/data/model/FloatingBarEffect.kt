package com.lonx.lyrico.data.model

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.floating_bar_effect_frosted_glass
import com.lonx.lyrico.resources.floating_bar_effect_liquid_glass
import com.lonx.lyrico.resources.floating_bar_effect_none
import org.jetbrains.compose.resources.StringResource

/**
 * 悬浮导航栏的视觉效果。
 *
 * 毛玻璃和液态玻璃是互斥的两种渲染方式，因此用同一个三选一设置表达，
 * 而不是两个可以同时打开的开关。
 */
enum class FloatingBarEffect(
    val labelRes: StringResource
) {
    NONE(Res.string.floating_bar_effect_none),
    FROSTED_GLASS(Res.string.floating_bar_effect_frosted_glass),
    LIQUID_GLASS(Res.string.floating_bar_effect_liquid_glass);

    companion object {
        /** 宽松解析持久化的枚举名，遇到未知值回退到 [NONE]。 */
        fun fromName(name: String?): FloatingBarEffect =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}
