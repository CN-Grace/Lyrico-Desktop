package com.lonx.lyrico.ui.components

import com.lonx.lyrico.data.editfield.EditFieldConfig
import com.lonx.lyrico.data.editfield.EditFieldConfigJson
import com.lonx.lyrico.data.editfield.EditFieldScene
import com.lonx.lyrico.data.editfield.toEditFieldBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 拖拽排序的状态机：手势期间本地顺序说了算，落盘回来的旧顺序不能把这一拖抹掉。
 *
 * 这四条在 Android 侧就在 `src/test/java` 下，那一整棵树不参与桌面构建（见 `build.gradle.kts` 里
 * 清空 `java.srcDirs` 的两行），所以 `FieldOrderState` 搬进 kotlin 树之前它们一次都没跑过。搬过来时
 * 只换了断言库（`org.junit.Assert` → `kotlin.test`），用例本身逐条照搬。
 */
class FieldOrderStateTest {

    @Test
    fun `drop callback created before drag saves all moves without recomposition`() {
        val config = EditFieldConfig()
        val state = FieldOrderState(config.allFields.toEditFieldBlocks().map { it.key })
        val onDrop = { config.withBlockOrder(state.order) }
        state.move("rating", "picture")
        state.move("lyrics", "rating")
        val saved = onDrop()
        assertEquals(
            listOf("lyrics", "rating", "picture"),
            saved.visibleFieldsForScene(EditFieldScene.SingleEdit).take(3).map { it.code },
        )
        assertEquals(state.order, saved.allFields.toEditFieldBlocks().map { it.key })
    }

    @Test
    fun `old disk emissions do not undo a pending drag and ack allows future external updates`() {
        val state = FieldOrderState(listOf("title", "album", "rating"))
        state.move("rating", "title")
        state.updateSaved(listOf("title", "album", "rating"))
        assertEquals(listOf("rating", "title", "album"), state.order)
        state.updateSaved(listOf("rating", "title", "album"))
        state.updateSaved(listOf("album", "title", "rating"))
        assertEquals(listOf("album", "title", "rating"), state.order)
    }

    @Test
    fun `member drag uses newest order at drop and survives persistence`() {
        val config = EditFieldConfig()
        val members = config.allFields.toEditFieldBlocks().single { it.isComposite }.fields.map { it.code }
        val state = FieldOrderState(members)
        val onDrop = { config.withComponentOrder("component:ReplayGain", state.order) }
        state.move("reference_loudness", "track_gain")
        state.move("album_peak", "track_peak")
        val saved = EditFieldConfigJson.decode(EditFieldConfigJson.from(onDrop()).encode(), null, null)
        assertEquals(
            listOf("reference_loudness", "track_gain", "album_peak", "track_peak", "album_gain"),
            saved.allFields.toEditFieldBlocks().single { it.isComposite }.fields.map { it.code },
        )
    }

    @Test
    fun `changed membership discards stale drag instead of reviving a deleted tag`() {
        val state = FieldOrderState(listOf("title", "tag:SOURCE", "album"))
        state.move("tag:SOURCE", "title")
        state.updateSaved(listOf("title", "album"))
        assertEquals(listOf("title", "album"), state.order)
        assertFalse(state.move("missing", "title"))
        assertFalse(state.move("title", "title"))
    }
}
