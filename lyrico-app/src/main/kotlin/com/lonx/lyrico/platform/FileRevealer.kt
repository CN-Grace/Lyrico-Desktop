package com.lonx.lyrico.platform

import java.io.File

/**
 * "Select this file in Explorer" — the desktop substitute for Android's share sheet.
 *
 * Android's share action sent `ACTION_SEND`/`ACTION_SEND_MULTIPLE` with `EXTRA_STREAM` and let the
 * system enumerate every app that accepts an audio MIME type. Windows has no such in-process enumeration, and
 * the closest honest equivalent of *"hand this file to another program"* is to open the folder
 * containing it with the file selected, so the user can drop it into whatever they want (PLAN.md
 * decision). This interface is that one operation.
 *
 * The process launch is injected for the same reason [DesktopOpener] is an interface: a test must be
 * able to assert the command line **without opening an Explorer window on the machine running it**.
 * Unlike `Desktop.open`, the reveal goes through `explorer.exe` rather than `java.awt.Desktop` because
 * `Desktop` has no "select this file" action — it can only *run* the file, which for an audio file
 * means playing it.
 */
interface FileRevealer {

    /**
     * Opens Explorer at [file]'s folder with [file] selected.
     *
     * [file] must exist and be absolute; the implementation is expected to throw when the shell refuses
     * so the caller can tell "shown" from "not shown".
     */
    fun reveal(file: File)
}

/**
 * The real thing: `explorer.exe /select,<path>`.
 *
 * Two Windows details this has to get right:
 *
 * - **The `/select,` argument is one token, comma-joined to the path with no space.** `explorer.exe`
 *   accepts `/select,"C:\path"` as a single argument; passing `/select` and the path as two arguments
 *   makes Explorer open *Documents* instead. `ProcessBuilder` quotes the whole token when it contains
 *   spaces, which is what the command line needs.
 * - **Explorer's exit code is not a success signal.** `explorer.exe` returns 1 even when it succeeded,
 *   because it hands the request to the already-running shell process. So the launcher is only
 *   reported as having failed when it throws (no `explorer.exe`, no permission), never on exit code.
 */
class ExplorerFileRevealer(
    private val launcher: (List<String>) -> Unit = { command ->
        ProcessBuilder(command).start()
    },
) : FileRevealer {

    override fun reveal(file: File) {
        val absolute = file.absoluteFile
        launcher(listOf("explorer.exe", "/select,${absolute.path}"))
    }
}
