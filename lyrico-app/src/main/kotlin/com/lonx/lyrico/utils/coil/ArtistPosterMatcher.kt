package com.lonx.lyrico.utils.coil

import com.lonx.lyrico.data.model.CharacterMappingDefaults
import java.text.Normalizer

/**
 * Matches poster file names against artist names.
 *
 * A file name cannot contain the characters the app itself treats as invalid
 * ([CharacterMappingDefaults.DEFAULT_INVALID_CHARS]: `\ / : * ? " < > |`), so an artist named
 * `AC/DC` can only be stored as `AC_DC.jpg`, `AC-DC.jpg`, `AC DC.jpg`, `ACDC.jpg` - or as
 * `AC／DC.jpg`, because the app's own file renaming replaces them with full-width characters.
 * Those characters are therefore interchangeable with one separator, with their full-width form,
 * or with nothing at all. Every other character - including punctuation that *is* legal in a file
 * name, such as the dots of `G.E.M. 邓紫棋` - must match as-is.
 *
 * Ranks, lowest wins:
 * - `0` exact name (`周杰伦.jpg`, `AC_DC.jpg` for `AC_DC`, `AC／DC.jpg` for `AC/DC`)
 * - `1` same name once invalid characters are dropped or replaced
 *   (`AC/DC` -> `AC_DC.jpg`, `AC-DC.jpg`, `AC DC.jpg`, `ACDC.jpg`)
 * - `2` suffixed variant, i.e. the name followed by `_` (`Artist_live.jpg`, `AC_DC_live.jpg`)
 * - `null` the file does not belong to that artist
 *
 * The name must end (or be followed by `_`) exactly where the artist name ends, so
 * `ArtistExtra.jpg` never matches `Artist` and `伍佰 & China Blue.jpg` never matches `伍佰`.
 */
internal object ArtistPosterMatcher {
    /** File extensions that can be used as an artist poster. */
    val extensions = setOf("jpg", "jpeg", "png", "webp", "mp4")

    /**
     * Characters that a file name cannot hold, taken from the rule the app itself uses when it
     * renames files ([CharacterMappingDefaults.DEFAULT_INVALID_CHARS]).
     */
    internal val invalidFileNameChars: Set<Char> =
        CharacterMappingDefaults.DEFAULT_INVALID_CHARS.charMappings.keys
            .mapNotNullTo(mutableSetOf()) { it.singleOrNull() }

    /** Legal characters a file may use in place of an invalid one. */
    private const val FILE_NAME_SEPARATORS = "_- ."

    fun isPosterFile(fileName: String?): Boolean =
        fileName != null && fileName.substringAfterLast('.', "").lowercase() in extensions

    fun rank(fileName: String, artistName: String): Int? {
        if (!isPosterFile(fileName)) return null
        val artist = artistName.normalizeName()
        if (artist.isEmpty()) return null
        return rankStem(fileName.substringBeforeLast('.').normalizeName(), artist)
    }

    /**
     * Counts [fileNames] that belong to at least one of [artistNames], normalizing every artist
     * name once instead of for each file/artist pair.
     */
    fun countMatchedFiles(fileNames: List<String>, artistNames: List<String>): Int {
        if (fileNames.isEmpty() || artistNames.isEmpty()) return 0
        val artists = artistNames.map { it.normalizeName() }.filter { it.isNotEmpty() }.distinct()
        if (artists.isEmpty()) return 0
        return fileNames.count { name ->
            if (!isPosterFile(name)) return@count false
            val stem = name.substringBeforeLast('.').normalizeName()
            artists.any { artist -> rankStem(stem, artist) != null }
        }
    }

    private fun rankStem(stem: String, artist: String): Int? {
        if (stem == artist) return 0
        val end = tolerantPrefixEnd(stem, artist)
        return when {
            end < 0 -> null
            end == stem.length -> 1
            stem[end] == '_' -> 2
            else -> null
        }
    }

    /**
     * Returns the index in [stem] right after a match of [artist] that started at index `0`,
     * or `-1` when the artist name does not match there. Characters a file name cannot hold may
     * be missing from the stem or replaced by a run of separators.
     */
    private fun tolerantPrefixEnd(stem: String, artist: String): Int {
        var stemIndex = 0
        var artistIndex = 0
        while (artistIndex < artist.length) {
            if (artist[artistIndex] in invalidFileNameChars) {
                while (artistIndex < artist.length && artist[artistIndex] in invalidFileNameChars) {
                    artistIndex++
                }
                while (stemIndex < stem.length && stem[stemIndex].isInvalidReplacement()) {
                    stemIndex++
                }
                continue
            }
            if (stemIndex >= stem.length || stem[stemIndex] != artist[artistIndex]) return -1
            stemIndex++
            artistIndex++
        }
        return stemIndex
    }

    /**
     * Tag values and file names are typed independently. NFKC folds the full-width characters the
     * app renames invalid characters to back to their ASCII form, and handles the Unicode
     * composition differences of accents.
     */
    private fun String.normalizeName(): String =
        Normalizer.normalize(trim(), Normalizer.Form.NFKC).lowercase()

    private fun Char.isInvalidReplacement(): Boolean =
        this in invalidFileNameChars || this in FILE_NAME_SEPARATORS
}
