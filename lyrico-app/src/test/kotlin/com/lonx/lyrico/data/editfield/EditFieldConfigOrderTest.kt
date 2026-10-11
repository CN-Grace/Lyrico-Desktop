package com.lonx.lyrico.data.editfield

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 旧配置 / 导入的备份缺少某些 code 时的兜底行为。
 *
 * 这些用例防止"版本升级新增字段后老用户看不到新字段"或"顺序被打乱"。
 */
class EditFieldConfigOrderTest {

    @Test
    fun `fields missing from a stored order are appended in registry order`() {
        // 模拟一份旧备份：只有顺序的前两项，其余字段都不在列表里。
        val partial = EditFieldRegistry.defaultOrder.take(2)
        val config = EditFieldConfig(order = partial)

        val codes = config.orderedCodes()

        // 已有顺序保持在前。
        assertEquals(partial, codes.take(2))
        // 其余字段按注册表默认顺序补齐，一个都不少。
        assertEquals(EditFieldRegistry.defaultOrder.size, codes.size)
        assertEquals(EditFieldRegistry.defaultOrder, codes)
    }

    @Test
    fun `custom tags survive an order that does not mention them`() {
        val config = EditFieldConfig(customTags = listOf("SOURCE"))

        val codes = config.orderedCodes()

        assertTrue(codes.contains(EditFieldRegistry.customTagCode("SOURCE")))
        // 自定义标签排在所有内置字段之后。
        assertEquals(
            EditFieldRegistry.customTagCode("SOURCE"),
            codes.last(),
        )
    }

    @Test
    fun `removing a custom tag keeps the rest of the order intact`() {
        val config = EditFieldConfig()
            .withAddedCustomTag("SOURCE")
            .withAddedCustomTag("MOOD")

        val removed = config.withRemovedCustomTag("SOURCE")

        assertFalse(removed.orderedCodes().contains(EditFieldRegistry.customTagCode("SOURCE")))
        assertEquals(
            EditFieldRegistry.defaultOrder + EditFieldRegistry.customTagCode("MOOD"),
            removed.orderedCodes(),
        )
    }

    @Test
    fun `enabled counts follow overrides for standard and custom fields`() {
        val config = EditFieldConfig()
            .withAddedCustomTag("SOURCE")
            .withEnabled("title", false)

        assertFalse(config.isEnabled("title"))
        assertTrue(config.isEnabled(EditFieldRegistry.customTagCode("SOURCE")))

        // 关掉自定义标签后它不再出现在编辑页的可见列表里。
        val disabled = config.withEnabled(EditFieldRegistry.customTagCode("SOURCE"), false)
        assertTrue(disabled.visibleCustomTagKeys(EditFieldScene.SingleEdit).isEmpty())
    }
}
