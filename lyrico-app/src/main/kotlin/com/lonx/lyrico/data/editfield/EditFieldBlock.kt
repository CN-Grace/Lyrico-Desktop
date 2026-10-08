package com.lonx.lyrico.data.editfield

/** 主列表、编辑器共用的排序单元；只有回放增益包含多个可排序字段。 */
data class EditFieldBlock(val key: String, val fields: List<EditFieldDefinition>) {
    val kind: EditFieldKind get() = fields.first().kind
    val isComposite: Boolean get() = kind == EditFieldKind.ReplayGain
}

fun List<EditFieldDefinition>.toEditFieldBlocks(): List<EditFieldBlock> =
    groupBy { it.componentKey ?: it.code }.map { (key, fields) -> EditFieldBlock(key, fields) }

val EditFieldDefinition.componentKey: String?
    get() = if (kind == EditFieldKind.ReplayGain) "component:ReplayGain" else null
