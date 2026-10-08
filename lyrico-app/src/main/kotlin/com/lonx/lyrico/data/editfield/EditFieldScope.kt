package com.lonx.lyrico.data.editfield

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.edit_field_scope_batch_edit
import com.lonx.lyrico.resources.edit_field_scope_both
import com.lonx.lyrico.resources.edit_field_scope_single_edit
import org.jetbrains.compose.resources.StringResource

enum class EditFieldScope {
    SingleEdit,
    BatchEdit,
    Both;

    fun supports(scene: EditFieldScene): Boolean {
        return when (this) {
            SingleEdit -> scene == EditFieldScene.SingleEdit
            BatchEdit -> scene == EditFieldScene.BatchEdit
            Both -> true
        }
    }
    fun toStringRes(): StringResource {
        return when (this) {
            SingleEdit -> Res.string.edit_field_scope_single_edit
            BatchEdit -> Res.string.edit_field_scope_batch_edit
            Both -> Res.string.edit_field_scope_both
        }
    }
}
