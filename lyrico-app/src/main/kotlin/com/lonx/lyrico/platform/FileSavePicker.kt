package com.lonx.lyrico.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * "Where should this file be written?" -- the desktop replacement for the Android save flow.
 *
 * Android asked the Storage Access Framework (`rememberLauncherForActivityResult` with the
 * `ActivityResultContracts.CreateDocument` contract, wildcard MIME type) and got back a content `Uri`
 * the app did not own. On Windows the user picks a path in a native dialog and the app gets a real
 * [File], so the ported use cases keep taking `File` and no URI layer is reintroduced.
 *
 * It is an interface rather than a direct `FileDialog` call so a test can drive the export without a
 * human: the export path (bytes written, ids used, toast shown) is real code under test, and the only
 * thing replaced is the OS dialog -- the same seam rule the rest of the port follows. The real
 * implementation is not a fake, it is the actual Windows save dialog.
 */
fun interface FileSavePicker {
    /**
     * Returns the file to write to, or `null` when the user cancelled.
     *
     * [defaultFileName] is what the dialog pre-fills, which is where Android's
     * `exportLauncher.launch("lyrico_log_<timestamp>.log")` suggestion goes.
     */
    suspend fun pick(defaultFileName: String): File?
}

/**
 * The real picker: AWT's native save dialog.
 *
 * Two deliberate choices:
 *
 * * **No parent [Frame].** Compose Desktop does not expose its window through a stable public
 *   `CompositionLocal`, and an owner-less dialog is still a native modal save dialog on Windows. The
 *   cost is that it is not window-modal to the Compose window (it can go behind it); the benefit is no
 *   reflection into Compose internals. Revisit if a public owner accessor appears.
 * * **A `File` directory plus the dialog's own file name**, rather than string-concatenating a path:
 *   Windows path separators and a user-typed name both stay the OS's business.
 */
@Composable
fun rememberFileSavePicker(title: String = "Save"): FileSavePicker = remember(title) {
    FileSavePicker { defaultFileName ->
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply {
            file = defaultFileName
            isVisible = true
        }
        val directory = dialog.directory
        val fileName = dialog.file
        dialog.dispose()
        if (directory == null || fileName == null) null else File(directory, fileName)
    }
}
