package com.lonx.audiotag.internal;

import com.lonx.audiotag.model.AudioProperties;
import com.lonx.audiotag.model.Metadata;
import com.lonx.audiotag.model.Picture;

import java.util.HashMap;

/**
 * The JNI boundary of the audio tag bridge - one Java method per exported C function.
 *
 * <p>This is deliberately plain Java rather than a Kotlin {@code object} with {@code @JvmStatic
 * external fun}s, and the reason is a JNI binding hazard that is worth spelling out:
 * <ol>
 *   <li>HotSpot resolves a native method by the <em>short</em> symbol name
 *       ({@code Java_com_lonx_audiotag_internal_AudioTagNative_getMetadata}) when that name is
 *       unambiguous, and that symbol carries no signature information.</li>
 *   <li>Kotlin compiles {@code @JvmStatic fun} in an {@code object} into <b>two</b> methods - the
 *       instance implementation plus a static bridge. For {@code external} declarations that pair
 *       can leave two native methods sharing one name, and both then bind to the same C function.
 *       Calls through the instance form pass a {@code jobject} where the C code expects the first
 *       real argument, which fails <em>silently</em> (an empty file path, a null result) instead of
 *       raising {@link UnsatisfiedLinkError}.</li>
 * </ol>
 * Writing the boundary in Java makes the emitted bytecode exactly what is written here - a single
 * static native method per name - so the short-name lookup can never bind the wrong one. The
 * friendly, {@link java.nio.file.Path}-based API lives in {@code com.lonx.audiotag.TagLib}.
 *
 * <p>The native library is loaded by this class's static initialiser. Loading goes through
 * {@link NativeLibraryLoader}, so it searches the packaged application directory and the build
 * output before falling back to {@code java.library.path}.
 */
public final class AudioTagNative {

    static {
        NativeLibraryLoader.load();
    }

    private AudioTagNative() {
    }

    /**
     * @param path absolute path of the audio file
     * @param readStyle {@code AudioPropertiesReadStyle.ordinal}
     */
    public static native AudioProperties getAudioProperties(String path, int readStyle);

    /**
     * @param path absolute path of the audio file
     * @param readPictures whether embedded artwork should be decoded as well
     */
    public static native Metadata getMetadata(String path, boolean readPictures);

    /** Values of a single property, or {@code null} when the file has no such property. */
    public static native String[] getMetadataPropertyValues(String path, String propertyName);

    /** All embedded pictures, in tag order. */
    public static native Picture[] getPictures(String path);

    /**
     * Applies {@code propertyMap} on top of the file's existing properties and saves.
     * An empty array (or a single empty string) for a key removes it.
     */
    public static native boolean savePropertyMap(String path, HashMap<String, String[]> propertyMap);

    /** Replaces the embedded pictures and saves. */
    public static native boolean savePictures(String path, Picture[] pictures);
}
