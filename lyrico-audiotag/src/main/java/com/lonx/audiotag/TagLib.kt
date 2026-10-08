package com.lonx.audiotag

import com.lonx.audiotag.internal.AudioTagNative
import com.lonx.audiotag.internal.NativeLibraryLoader
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.audiotag.model.AudioProperties
import com.lonx.audiotag.model.AudioPropertiesReadStyle
import com.lonx.audiotag.model.Metadata
import com.lonx.audiotag.model.Picture
import com.lonx.audiotag.model.PropertyMap
import com.lonx.audiotag.model.artistPictureTypes
import java.nio.file.Path

/**
 * Read and write audio metadata through the native TagLib bridge.
 *
 * Desktop port note: the Android build passed a POSIX file descriptor into the native layer
 * (`FdUtils.getNativeFd`, a `dup()` + `detachFd()` pair). Windows has no integer file-descriptor
 * model, so the bridge is path-based instead - every entry point takes the path of the audio file
 * and the native side opens its own stream. Paths are made absolute here so the native side never
 * depends on the process working directory.
 *
 * The native declarations themselves live in [AudioTagNative]; see that class for why the JNI
 * boundary is written in Java.
 */
public object TagLib {

    /**
     * Loads the native bridge. Called automatically on first use; call it explicitly during
     * application startup to surface a packaging problem before the user opens a file.
     *
     * @param searchDirs extra directories to probe, in addition to the defaults
     *   (see [NativeLibraryLoader.defaultSearchDirs]).
     */
    @JvmStatic
    @JvmOverloads
    public fun ensureLoaded(searchDirs: List<Path>? = null) {
        NativeLibraryLoader.load(
            libraryName = NativeLibraryLoader.TAGLIB,
            searchDirs = searchDirs ?: NativeLibraryLoader.defaultSearchDirs(),
        )
    }

    /** Whether the native bridge has been loaded successfully. */
    @JvmStatic
    public fun isLoaded(): Boolean = NativeLibraryLoader.isLoaded(NativeLibraryLoader.TAGLIB)

    private fun nativePath(path: Path): String = path.toAbsolutePath().normalize().toString()

    /**
     * Get audio properties from a file path.
     *
     * @param path Audio file path
     * @param readStyle Read style for audio properties to balance speed and accuracy
     */
    @JvmStatic
    @JvmOverloads
    public fun getAudioProperties(
        path: Path,
        readStyle: AudioPropertiesReadStyle = AudioPropertiesReadStyle.Average,
    ): AudioProperties? = AudioTagNative.getAudioProperties(nativePath(path), readStyle.ordinal)

    /**
     * Get metadata from a file path.
     *
     * @param path Audio file path
     * @param readPictures Whether to read pictures
     */
    @JvmStatic
    @JvmOverloads
    public fun getMetadata(
        path: Path,
        readPictures: Boolean = true,
    ): Metadata? = AudioTagNative.getMetadata(nativePath(path), readPictures)

    /**
     * Get metadata property values from a file path.
     *
     * @param path Audio file path
     * @param propertyName Property name
     */
    @JvmStatic
    public fun getMetadataPropertyValues(
        path: Path,
        propertyName: String,
    ): Array<String>? = AudioTagNative.getMetadataPropertyValues(nativePath(path), propertyName)

    /**
     * Get pictures from a file path. There may be multiple pictures with different types.
     */
    @JvmStatic
    public fun getPictures(path: Path): Array<Picture> =
        AudioTagNative.getPictures(nativePath(path)) ?: emptyArray()

    /**
     * Get picture with the requested type from a file path.
     *
     * @param description Artist name this artwork belongs to. Artist artwork may contain several
     *   pictures distinguished by their description, so when it is given:
     *   1. a picture whose description matches (ignoring case and surrounding space) is used;
     *   2. otherwise a picture **without** a description is used - those are files written before
     *      descriptions were used, so they cannot contradict the request;
     *   3. otherwise [fallbackToAny] decides: true returns a picture that carries *another* artist's
     *      description. That is a last resort the caller is expected to try only after its own
     *      alternatives (e.g. the external poster folders), because hiding the picture entirely would
     *      leave the user with no way to see - or re-assign - what is actually in the tag.
     */
    @JvmStatic
    @JvmOverloads
    public fun getPicture(
        path: Path,
        pictureType: AudioPictureType,
        fallbackPictureTypes: List<AudioPictureType> = emptyList(),
        fallbackToAny: Boolean = false,
        description: String? = null,
    ): Picture? {
        val pictures = getPictures(path)
        val types = listOf(pictureType) + fallbackPictureTypes
        val requested = description?.trim()?.takeIf { it.isNotEmpty() }

        if (requested != null) {
            return types.firstNotNullOfOrNull { type ->
                pictures.find { picture ->
                    picture.pictureType == type.tagLibName &&
                        picture.description.trim().equals(requested, ignoreCase = true)
                }
            }
                ?: types.firstNotNullOfOrNull { type ->
                    pictures.find { picture ->
                        picture.pictureType == type.tagLibName && picture.description.isBlank()
                    }
                }
                ?: if (fallbackToAny) pictures.firstOrNull() else null
        }

        return pictures.find { picture -> picture.pictureType == pictureType.tagLibName }
            ?: fallbackPictureTypes.firstNotNullOfOrNull { fallbackType ->
                pictures.find { picture -> picture.pictureType == fallbackType.tagLibName }
            }
            ?: if (fallbackToAny) pictures.firstOrNull() else null
    }

    /**
     * Get front cover from a file path.
     */
    @JvmStatic
    public fun getFrontCover(path: Path): Picture? {
        return getPicture(
            path = path,
            pictureType = AudioPictureType.FrontCover,
            fallbackToAny = true
        )
    }

    /**
     * Convenience wrapper used by artist artwork lookups: the artist picture types are tried in
     * order, matching the artist name in the picture description when one is given.
     */
    @JvmStatic
    public fun getArtistPicture(path: Path, artist: String?): Picture? {
        return artistPictureTypes.firstNotNullOfOrNull { type ->
            getPicture(path = path, pictureType = type, description = artist)
        }
    }

    /**
     * Save metadata for a file path.
     *
     * @param path Audio file path
     * @param propertyMap Property map to save
     *
     * @return Whether the operation was successful
     */
    @JvmStatic
    public fun savePropertyMap(
        path: Path,
        propertyMap: PropertyMap,
    ): Boolean = AudioTagNative.savePropertyMap(nativePath(path), propertyMap)

    /**
     * Save pictures for a file path.
     *
     * @param path Audio file path
     * @param pictures Pictures to save
     *
     * @return Whether the operation was successful
     */
    @JvmStatic
    public fun savePictures(
        path: Path,
        pictures: Array<Picture>,
    ): Boolean = AudioTagNative.savePictures(nativePath(path), pictures)
}
