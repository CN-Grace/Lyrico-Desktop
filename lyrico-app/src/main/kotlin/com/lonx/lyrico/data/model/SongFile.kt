package com.lonx.lyrico.data.model

import java.nio.file.Path

/**
 * A song file found by a scan, before its tags are read.
 *
 * Android's original carried a `Uri` and, for the MediaStore source, a `Long` row id. On Windows
 * the file identity is its absolute path — the same value the `songs.uri` column stores — so [path]
 * replaces `uri` and every consumer reads the path from there.
 *
 * The path is already canonical (absolute, real casing, no `.`/`..`) — see `SongPaths.canonicalize`.
 * Canonical form matters because `songs.uri` is UNIQUE: two spellings of one file would otherwise
 * become two rows.
 */
data class SongFile(
    val mediaId: Long,
    val path: Path,
    val filePath: String,
    val fileName: String,
    val lastModified: Long,
    val dateAdded: Long,
    val duration: Long = 0,
    val fileSize: Long = 0
)
