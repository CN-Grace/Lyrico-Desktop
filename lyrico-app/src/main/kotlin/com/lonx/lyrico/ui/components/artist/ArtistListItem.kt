package com.lonx.lyrico.ui.components.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.lyrico.data.model.entity.ArtistEntity
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.album_song_count
import com.lonx.lyrico.ui.components.CoverCandidate
import com.lonx.lyrico.ui.components.cover.CoverImage
import com.lonx.lyrico.utils.formattedStringResource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One artist row: portrait cover, name, and the "N albums · M songs" summary.
 *
 * Three things changed from the Android original:
 *
 * * `stringResource(R.string.album_song_count, ...)` became [formattedStringResource]. Both
 *   placeholders in that string are positional (`%1$d` / `%2$d`), so the library's own vararg
 *   formatting would in fact have worked here -- the helper is used anyway so the port has exactly
 *   one way to format a resource, and the guard test that enforces it has no exceptions to know
 *   about.
 * * `CoverCandidate(uri = candidate.uri.toUri())` became a plain [String]: desktop cover candidates
 *   are filesystem paths (`ui/components/CoverRequest.kt`), not `content://` URIs.
 * * `@SuppressLint("DefaultLocale")` is gone. It existed because Android lint flags locale-sensitive
 *   `String.format`; nothing in this file formats a number itself any more.
 */
@Composable
fun ArtistListItem(
    artist: ArtistEntity,
    modifier: Modifier = Modifier,
    coverCandidates: List<CoverCandidate> = emptyList(),
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .clickable(
                onClick = {
                    onClick()
                }
            )
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            CoverImage(
                uri = artist.coverSongUri,
                lastModified = artist.coverSongLastModified,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                pictureType = AudioPictureType.Artist,
                fallbackPictureTypes = listOf(
                    AudioPictureType.LeadArtist,
                    AudioPictureType.Band
                ),
                fallbackToAny = true,
                candidates = coverCandidates,
                artistName = artist.name
            )


            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = artist.name,
                    maxLines = 1,
                    fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formattedStringResource(
                            Res.string.album_song_count,
                            artist.albumCount,
                            artist.songCount
                        ),
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            trailingContent?.let {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    trailingContent()
                }
            }
        }
    }
}
