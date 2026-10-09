package com.lonx.lyrico.ui.components.cover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.lyrico.ui.components.CoverCandidate
import com.lonx.lyrico.ui.components.CoverRequest
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CoverImage(
    uri: String?,
    lastModified: Long,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    contentDescription: String? = null,
    pictureType: AudioPictureType = AudioPictureType.FrontCover,
    fallbackPictureTypes: List<AudioPictureType> = emptyList(),
    fallbackToAny: Boolean = pictureType == AudioPictureType.FrontCover,
    candidates: List<CoverCandidate> = emptyList(),
    artistName: String? = null
) {
    val posterSource = rememberArtistPosterSource(enabled = artistName != null)
    val hasSource = (!uri.isNullOrBlank() && uri != "0") || artistName != null
    val request = remember(
        uri,
        lastModified,
        pictureType,
        fallbackPictureTypes,
        fallbackToAny,
        candidates,
        artistName,
        posterSource
    ) {
        CoverRequest(
            uri = uri ?: "",
            lastUpdate = lastModified,
            pictureType = pictureType,
            fallbackPictureTypes = fallbackPictureTypes,
            fallbackToAny = fallbackToAny,
            candidates = candidates,
            artistName = artistName,
            artistPosterFolders = listOfNotNull(posterSource.folder),
            artistPosterRevision = posterSource.revision
        )
    }
    // An artist without embedded artwork and without a matching poster has nothing to show,
    // so keep the placeholder icon instead of an empty box.
    var loadFailed by remember(request) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!hasSource || loadFailed) {
            Icon(
                imageVector = MiuixIcons.Image,
                contentDescription = contentDescription,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(26.dp)
            )
        }
        if (hasSource) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { loadFailed = false },
                onError = { loadFailed = true }
            )
        }
    }
}
