package com.lonx.lyrico.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 手势回调读取同一个状态对象，避免松手时写回手势开始前捕获的列表。 */
internal class FieldOrderState(initialOrder: List<String>) {
    private var savedOrder by mutableStateOf(initialOrder)
    private var pendingOrder by mutableStateOf<List<String>?>(null)
    val order: List<String> get() = pendingOrder ?: savedOrder

    fun move(fromKey: Any, toKey: Any): Boolean {
        val current = order
        val from = current.indexOf(fromKey)
        val to = current.indexOf(toKey)
        if (from < 0 || to < 0 || from == to) return false
        pendingOrder = current.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }

    fun updateSaved(order: List<String>) {
        savedOrder = order
        val pending = pendingOrder ?: return
        // 等持久化状态追上本地顺序再释放，忽略写盘期间较旧的同集合顺序。
        if (pending == order || pending.toSet() != order.toSet()) pendingOrder = null
    }

    fun resetPending() { pendingOrder = null }
}
