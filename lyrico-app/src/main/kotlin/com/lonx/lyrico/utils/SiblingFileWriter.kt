package com.lonx.lyrico.utils

import java.io.File
import java.io.OutputStream

/**
 * Writes a file next to another one -- the desktop replacement for `SafSiblingFileWriter`.
 *
 * Android's version existed because a `content://` document has no path: to put `cover.jpg` beside
 * `song.mp3` it had to ask `DocumentsContract` for the parent document id (walking the tree,
 * breadth-first, when the provider would not answer) and then either find the existing child with
 * that display name or create one. On Windows the audio file *is* the path, so the whole walk
 * collapses to `File(parent, fileName)` -- the same collapse
 * [com.lonx.lyrico.worker.processor.BatchExportProcessor.siblingOfAudio] already documents for the
 * export worker.
 *
 * What the Android version did that the two-line version must keep:
 *
 * * **Overwrite, never append.** Android opened the stream in `"wt"` mode. `File.outputStream()`
 *   truncates too, so re-exporting a cover replaces the previous bytes instead of growing the file.
 * * **Refuse when there is no folder to write into.** Android threw `IllegalStateException("Source
 *   folder unavailable")` when the parent could not be resolved; a bare path with no parent (a
 *   relative file name) has the same problem here, and callers report the failure the same way.
 *
 * [write] returns the written [File] where Android returned the document `Uri`, because every caller
 * only wanted to log where the bytes went.
 */
object SiblingFileWriter {

    /** Writes [bytes] as [fileName] beside [audioFile]. */
    fun write(audioFile: File, fileName: String, bytes: ByteArray): File =
        write(audioFile, fileName) { outputStream -> outputStream.write(bytes) }

    /**
     * Creates [fileName] beside [audioFile] and hands the open stream to [writeContent].
     *
     * The stream is closed when [writeContent] returns -- and on the way out of a throw, so a failed
     * export leaves a truncated file rather than a half-written one that looks complete.
     */
    fun write(audioFile: File, fileName: String, writeContent: (OutputStream) -> Unit): File {
        val folder = audioFile.absoluteFile.parentFile
            ?: throw IllegalStateException("Source folder unavailable")
        val target = File(folder, fileName)
        target.outputStream().use(writeContent)
        return target
    }
}
