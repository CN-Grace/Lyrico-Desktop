package com.lonx.lyrico.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/**
 * "Which folder should be added to the library?" -- the desktop replacement for the Android folder
 * flow.
 *
 * Android asked the Storage Access Framework (`ActivityResultContracts.OpenDocumentTree`), got a tree
 * `Uri`, took a persistable read/write permission on it and then mapped the URI back to a filesystem
 * path with `UriUtils.getFileAbsolutePath`. None of that survives the port: on Windows the user picks
 * a real directory and the app already has full read/write access to it, so the caller receives a
 * [File] and [com.lonx.lyrico.viewmodel.SongListViewModel.addFolderAndRefresh] takes its absolute
 * path. `UriUtils` is therefore not ported at all.
 *
 * It is an interface rather than a direct chooser call so a test can drive "the user added a folder"
 * end to end (real Room, real scan of a real directory) without a human clicking through a native
 * dialog -- the same seam rule the rest of the port follows, and the same shape as [FileSavePicker].
 */
fun interface DirectoryPicker {
    /** Returns the chosen directory, or `null` when the user cancelled. */
    suspend fun pick(): File?
}

/**
 * The real picker: Swing's directory chooser.
 *
 * AWT's [java.awt.FileDialog], which [rememberFileSavePicker] uses for saving, cannot pick
 * directories on Windows, so this is `JFileChooser` with `DIRECTORIES_ONLY`. It is shown on the AWT
 * event thread via `SwingUtilities.invokeLater` rather than being called inline: a caller on that
 * thread would otherwise block it for as long as the modal dialog is up, and a caller off it would
 * touch Swing off-thread. Posting also makes `SwingUtilities.invokeAndWait` unnecessary, so the
 * dialog can never deadlock against Compose Desktop's own Swing dispatcher.
 *
 * [title] is the dialog's caption; the caller passes localised text so no string is baked in here.
 *
 * Note that cancelling the coroutine while the dialog is open does not close the dialog -- the user
 * still has to dismiss it, and the resume is then dropped. Nothing in the app cancels this call.
 */
@Composable
fun rememberDirectoryPicker(title: String): DirectoryPicker = remember(title) {
    DirectoryPicker { pickDirectory(title) }
}

private suspend fun pickDirectory(title: String): File? = suspendCancellableCoroutine { continuation ->
    SwingUtilities.invokeLater {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
            // Follow Windows' own convention for folder pickers, where "Open" accepts a folder.
            approveButtonText = "OK"
        }
        val result = chooser.showOpenDialog(null)
        val selected = if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
        if (continuation.isActive) {
            continuation.resume(selected)
        }
    }
}
