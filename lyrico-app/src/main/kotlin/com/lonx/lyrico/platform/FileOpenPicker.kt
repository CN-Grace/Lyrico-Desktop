package com.lonx.lyrico.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * "Which file should be opened?" -- the desktop replacement for Android's document-open flow.
 *
 * Android asked the Storage Access Framework: `rememberLauncherForActivityResult` with
 * `ActivityResultContracts.OpenDocument()`, filtered to `application/zip`, and got back a content
 * `Uri` that
 * [com.lonx.lyrico.viewmodel.PluginViewModel.importPlugin] then read through
 * `Context.contentResolver`. On Windows the user picks a real path in a native dialog, so the
 * view model takes an absolute path and reads the archive with a plain `File.inputStream()` -- the
 * same substitution [FileSavePicker] made for the export flow, and the reason `UriUtils`/`SafDocuments`
 * are not ported at all.
 *
 * What Android did *not* need replacing: the plugin installer still validates the archive itself
 * (entry names, sizes, manifest, host-API version), so this seam decides nothing about acceptability.
 * A user can still pick any file; the installer refuses the ones that are not plugin packages.
 *
 * It is an interface rather than a direct dialog call so a test can drive "the user picked this zip"
 * end to end -- real archive, real [com.lonx.lyrico.plugin.source.SourcePluginInstaller], real
 * QuickJS runtime -- with only the OS dialog replaced. Same seam rule as [DirectoryPicker] and
 * [FileSavePicker].
 */
fun interface FileOpenPicker {
    /** Returns the chosen file, or `null` when the user cancelled. */
    suspend fun pick(): File?
}

/**
 * The real picker: AWT's native open dialog, filtered to zip files.
 *
 * [title] is the dialog's caption, passed in already localised so no string is baked in here, exactly
 * as [rememberDirectoryPicker] does it. The parent is deliberately `null` for the reason
 * [rememberFileSavePicker] documents: Compose Desktop exposes no stable public window accessor, and an
 * owner-less dialog is still a native modal dialog on Windows.
 *
 * [FileDialog.setFilenameFilter] is a no-op on Windows -- the JVM's Windows implementation ignores it
 * and filters through the `file` pattern the OS understands instead -- so it is set but not relied on;
 * it does apply on the other platforms this build could be run on.
 */
@Composable
fun rememberFileOpenPicker(title: String): FileOpenPicker = remember(title) {
    FileOpenPicker { pickFile(title) }
}

private fun pickFile(title: String): File? {
    // Blocking, like FileSavePicker: AWT's modal dialog pumps its own event loop, so it can be called
    // straight from a coroutine on the UI dispatcher. Only the *picking* blocks -- the caller hands
    // the result to a background coroutine before touching the archive.
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
        filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
        file = "*.zip"
        isVisible = true
    }
    val directory = dialog.directory
    val fileName = dialog.file
    dialog.dispose()
    return if (directory == null || fileName == null) null else File(directory, fileName)
}
