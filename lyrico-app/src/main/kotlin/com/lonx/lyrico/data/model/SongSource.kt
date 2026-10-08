package com.lonx.lyrico.data.model

/**
 * Values of the `songs.source` column: where a library row came from.
 *
 * Android distinguished `MEDIA_STORE` (a file the system media index knew about) from `SAF` (a file
 * found by walking a user-granted folder tree). Windows has neither: every song is found by walking
 * a library folder on disk, so [LOCAL] is the only value the desktop build writes. The column is
 * kept because the schema is pinned, and because rows inherited from an Android database still say
 * `SAF`/`MEDIA_STORE` — code that has to recognise "not one of ours" should compare against those
 * legacy values explicitly rather than assuming everything is [LOCAL].
 */
object SongSource {
    /** A file discovered by scanning a library folder. */
    const val LOCAL = "LOCAL"

    /** Legacy Android value: the row was inserted from a granted SAF folder tree. */
    const val LEGACY_SAF = "SAF"

    /** Legacy Android value: the row was inserted from the system media index. */
    const val LEGACY_MEDIA_STORE = "MEDIA_STORE"
}
