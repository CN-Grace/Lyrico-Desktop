package com.lonx.lyrico.data.model

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * Windows path rules for everything that is stored in the database.
 *
 * The Android build used `content://` URIs as the identity of a song (`songs.uri` is a `UNIQUE`
 * column, and `SongEntity.path` is its typed view). The desktop build keeps that column and its
 * schema byte-for-byte identical but stores a **Windows absolute path** in it, so the same question
 * — "is this the same song?" — has to be answered for paths instead of URIs.
 *
 * Three rules follow from that, and every write to `songs.uri` / `songs.filePath` / the FTS
 * `songUri` column / custom-tag keys must go through this object:
 *
 * 1. **Stored paths are absolute and canonical** ([canonicalize]). Windows paths have many
 *    spellings for one file (`c:\Music\A.mp3`, `C:\Music\..\Music\A.mp3`, `C:\MUSIC\a.MP3`,
 *    `\\?\C:\Music\A.mp3`, an 8.3 short name, a junction/symlink target). For files that exist we
 *    ask the filesystem for the real path, so the stored value is the real casing of the file.
 *    For paths that are gone (a library folder on a disconnected drive) we fall back to
 *    `absolute().normalize()`, which keeps old rows readable instead of throwing.
 * 2. **Comparison is case-insensitive** ([identityKey]). NTFS is case-insensitive by default, so a
 *    pre-insert lookup must not treat `C:\Music\A.mp3` and `c:\music\A.mp3` as two songs. This is
 *    deliberately *not* solved with `COLLATE NOCASE` on the column: the desktop database has to
 *    keep the Android `identityHash` so an existing `lyrico.db` opens unchanged.
 * 3. **Non-path values are rejected** ([canonicalize] returns null). `content://` rows inherited
 *    from an Android database, blank strings and relative paths are not files on Windows.
 *
 * **Junction / symlink policy: one target is one library.** A directory reached through a junction
 * resolves to the real directory, so adding both `D:\MusicLink` (a junction to `E:\Music`) and
 * `E:\Music` yields one row per file, not two. The trade-off is that the stored path shows the
 * target (`E:\Music\...`) rather than the link the user picked; this is intentional — it is what
 * makes identity, renames and de-duplication work.
 */
object SongPaths {

    /**
     * Canonicalises a value that came from disk, the database or the UI.
     *
     * @return the absolute canonical path, or `null` when [raw] is not a Windows path at all
     *   (blank, a legacy `content://` uri, an unparseable or relative path).
     */
    fun canonicalize(raw: String?): Path? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parsed = try {
            Path.of(trimmed)
        } catch (_: InvalidPathException) {
            return null
        }
        // Checked before toAbsolutePath(), which would silently resolve a relative path against
        // the process working directory and make every spelling look valid.
        if (!stripExtendedPrefix(parsed).isAbsolute) return null
        return canonicalize(parsed)
    }

    /**
     * Canonicalises a path object. Prefer this overload for values that are already typed paths, so
     * a legacy `content://` string does not have to be faked into a [Path] first.
     */
    fun canonicalize(path: Path): Path {
        val absolute = stripExtendedPrefix(path).toAbsolutePath().normalize()
        // The file may be gone (unplugged drive, moved folder, deleted song): keep the normalized
        // form rather than failing, so scan results and settings stay readable.
        return try {
            absolute.toRealPath()
        } catch (_: Exception) {
            absolute
        }
    }

    /**
     * The value a case-insensitive lookup should use: an in-memory map key, or a `Files.exists`
     * style probe. Never stored in the database — the database holds [canonicalize] output so the
     * casing a user sees in Explorer is preserved.
     */
    fun identityKey(path: Path): String = canonicalize(path).toString().lowercase(Locale.ROOT)

    /** Same as [identityKey], for `content://`-style strings that may not resolve to a path. */
    fun identityKey(raw: String?): String? = canonicalize(raw)?.let(::identityKey)

    /**
     * `\\?\C:\Music` and `\\?\UNC\server\share` are valid to the Windows API but not to the rest of
     * the app (and `\\?\` paths skip normalization), so the prefix is removed before use.
     */
    private fun stripExtendedPrefix(path: Path): Path {
        val text = path.toString()
        val stripped = when {
            text.startsWith(EXTENDED_PREFIX_UNC, ignoreCase = true) ->
                UNC_PREFIX + text.substring(EXTENDED_PREFIX_UNC.length)

            text.startsWith(EXTENDED_PREFIX, ignoreCase = true) ->
                text.substring(EXTENDED_PREFIX.length)

            else -> return path
        }
        return try {
            Path.of(stripped)
        } catch (_: InvalidPathException) {
            path
        }
    }

    private const val EXTENDED_PREFIX = "\\\\?\\"
    private const val EXTENDED_PREFIX_UNC = "\\\\?\\UNC\\"
    private const val UNC_PREFIX = "\\\\"
}
