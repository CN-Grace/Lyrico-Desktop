package com.lonx.lyrico

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Completes one external edit request without affecting ordinary in-app navigation.
 *
 * On Android the host was the `Activity`: the screen reached it with `context as? ExternalAudioEditHost`
 * and called [finishExternalAudioEdit] when the user saved or cancelled, so whoever launched the edit
 * (a file manager doing "open with", a batch task) could be told the result. There is no Activity on the
 * desktop, and the caller is not behind a process boundary either -- it is the window that owns the whole
 * navigation graph.
 *
 * So the seam is a [androidx.compose.runtime.CompositionLocal] with a `null` default: the window (or a
 * test) provides the host for its subtree. `EditMetadataScreen` is the only caller; when a request id is
 * set and no host is present it logs the miss instead of swallowing it, and `PLAN.md` records the gap --
 * `LyricoDesktopApp` already threads the file-association argument in but does not provide a host yet.
 */
interface ExternalAudioEditHost {
    fun finishExternalAudioEdit(requestId: Long, saved: Boolean)
}

/**
 * The host for external edit requests in this subtree, or `null` when nothing launched one.
 *
 * `null` is the normal case for every screen the user reached by clicking: `externalEditRequestId` is a
 * navigation argument that only an external caller sets. `staticCompositionLocalOf` rather than
 * `compositionLocalOf`: a host is fixed when its subtree is created, so there is nothing to invalidate
 * when the value changes, and the cheaper read is the right trade.
 */
val LocalExternalAudioEditHost = staticCompositionLocalOf<ExternalAudioEditHost?> { null }
