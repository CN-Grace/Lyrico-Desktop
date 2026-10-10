package com.lonx.lyrico.utils

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.label_album
import com.lonx.lyrico.resources.label_album_artist
import com.lonx.lyrico.resources.label_artists
import com.lonx.lyrico.resources.label_disc_number
import com.lonx.lyrico.resources.label_genre
import com.lonx.lyrico.resources.label_title
import com.lonx.lyrico.resources.label_track_number
import com.lonx.lyrico.resources.label_year
import org.jetbrains.compose.resources.StringResource

/**
 * The rename-format placeholders (`@1`, `@2`, ...) and the tag field each one stands for.
 *
 * Android annotated [description] with `@StringRes` and stored a `R.string` id; on the desktop the
 * label is a compose resource like every other label in the app (`BatchTaskType.labelRes`), so a
 * caller renders it with `stringResource(field.description)`.
 */
enum class TagField(
    val index: Int,
    val description: StringResource
) {
    TITLE(1, Res.string.label_title),
    ARTIST(2, Res.string.label_artists),
    ALBUM_ARTIST(3, Res.string.label_album_artist),
    ALBUM(4, Res.string.label_album),
    TRACK(5, Res.string.label_track_number),
    DISC(6, Res.string.label_disc_number),
    YEAR(7, Res.string.label_year),
    GENRE(8, Res.string.label_genre);

    companion object {

        private val indexMap = entries.associateBy { it.index }

        fun fromIndex(index: Int): TagField? {
            return indexMap[index]
        }

        fun placeholder(index: Int): String {
            return "@$index"
        }

        fun placeholder(field: TagField): String {
            return "@${field.index}"
        }
    }
}
