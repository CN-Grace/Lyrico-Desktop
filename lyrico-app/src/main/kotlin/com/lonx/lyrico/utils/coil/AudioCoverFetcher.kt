package com.lonx.lyrico.utils.coil

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.audiotag.rw.AudioTagReader
import com.lonx.lyrico.ui.components.CoverCandidate
import com.lonx.lyrico.ui.components.CoverRequest
import okio.Buffer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves [CoverRequest]s by reading the file's embedded artwork through TagLib.
 *
 * The Android version took a `ContentResolver` and opened `content://` file descriptors. Desktop
 * files are addressed by path, so the resolver is gone and [AudioTagReader.readPicture] reads the
 * absolute path directly; the artwork-search order (requested type, then the artist's own poster
 * folder, then any picture at all) is unchanged.
 */
class AudioCoverFetcher(
    private val path: String,
    private val pictureType: AudioPictureType,
    private val fallbackPictureTypes: List<AudioPictureType>,
    private val fallbackToAny: Boolean,
    private val candidates: List<CoverCandidate>,
    private val artistName: String?,
    private val artistPosterFolders: List<String>,
    private val skipEmbeddedPictures: Boolean,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val candidateList = candidates.takeIf { it.isNotEmpty() }
            ?: listOf(CoverCandidate(path, 0L))

        val pictureBytes = withContext(Dispatchers.IO) {
            if (skipEmbeddedPictures) {
                // 调用方已经自己决定过内嵌海报归属，这里只查外置海报文件
                readExternalArtistPoster(artistName, artistPosterFolders)
            } else {
                readRequestedPicture(candidateList)
                    ?: readExternalArtistPoster(artistName, artistPosterFolders)
                    // 只有描述对不上的内嵌图（例如整首歌只有别的艺术家的海报）时，也要显示出来，
                    // 否则标签里明明有图却什么都不显示、用户也没法把它重新关联给某位艺术家。
                    // 放在外置海报之后：它是兜底，不该挡住这位艺术家自己的海报文件。
                    ?: readRequestedPicture(candidateList, includeOtherArtists = artistName != null)
                    ?: readFallbackPicture(candidateList)
            }
        } ?: return null

        if (pictureBytes.isEmpty()) {
            return null
        }


        val buffer = Buffer().apply { write(pictureBytes) }
        val imageSource = ImageSource(buffer, options.fileSystem)

        return SourceFetchResult(
            source = imageSource,
            mimeType = "image/*",
            dataSource = DataSource.DISK
        )
    }

    private suspend fun readRequestedPicture(
        candidates: List<CoverCandidate>,
        includeOtherArtists: Boolean = false
    ): ByteArray? {
        for (candidate in candidates) {
            // A missing or unreadable candidate must not stop the poster folder lookup below.
            val bytes = readArtworkSafely {
                AudioTagReader.readPicture(
                    path = File(candidate.uri).toPath(),
                    pictureType = pictureType,
                    fallbackPictureTypes = fallbackPictureTypes,
                    fallbackToAny = includeOtherArtists,
                    // 艺术家图片用描述记录归属：默认只认描述对得上（或没写描述）的那些
                    description = artistName
                )
            }
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    private suspend fun readFallbackPicture(
        candidates: List<CoverCandidate>
    ): ByteArray? {
        if (!fallbackToAny) return null
        val firstCandidate = candidates.firstOrNull() ?: return null
        return readArtworkSafely {
            AudioTagReader.readPicture(
                path = File(firstCandidate.uri).toPath(),
                pictureType = AudioPictureType.FrontCover,
                fallbackToAny = true
            )
        }
    }

    class Factory :
        Fetcher.Factory<CoverRequest> {
        override fun create(
            data: CoverRequest,
            options: Options,
            imageLoader: ImageLoader
        ) = AudioCoverFetcher(
            path = data.uri,
            pictureType = data.pictureType,
            fallbackPictureTypes = data.fallbackPictureTypes,
            fallbackToAny = data.fallbackToAny,
            candidates = data.candidates,
            artistName = data.artistName,
            artistPosterFolders = data.artistPosterFolders,
            skipEmbeddedPictures = data.skipEmbeddedPictures,
            options = options
        )
    }
}
