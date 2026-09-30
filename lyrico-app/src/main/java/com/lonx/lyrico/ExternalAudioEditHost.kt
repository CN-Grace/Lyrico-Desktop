package com.lonx.lyrico

/** Completes one external edit request without affecting ordinary in-app navigation. */
interface ExternalAudioEditHost {
    fun finishExternalAudioEdit(requestId: Long, saved: Boolean)
}
