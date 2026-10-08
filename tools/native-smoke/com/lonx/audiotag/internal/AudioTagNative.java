package com.lonx.audiotag.internal;

import com.lonx.audiotag.model.AudioProperties;
import com.lonx.audiotag.model.Metadata;
import com.lonx.audiotag.model.Picture;

import java.util.HashMap;

/**
 * Throwaway test double for the production JNI boundary {@code com.lonx.audiotag.internal.AudioTagNative}.
 *
 * <p>The native library exports the <em>short</em> JNI symbol names
 * ({@code Java_com_lonx_audiotag_internal_AudioTagNative_getMetadata} and friends), so this class must
 * be in the same package, carry the same name, and declare exactly the same method names with
 * parameter lists matching the C signatures — including the ported path-based ABI
 * ({@code jstring path} where Android used to pass {@code jint fd}).
 *
 * <p>This mirroring is the point of the harness: it loads the DLLs with a plain desktop JVM and
 * proves that the symbol names the C code exports are the ones the real boundary declares. Unlike
 * the production class it does not load anything itself — the harness loads the DLLs from an
 * explicit directory so it does not depend on {@code java.library.path}.
 */
public final class AudioTagNative {

    private AudioTagNative() {
    }

    public static native AudioProperties getAudioProperties(String path, int readStyle);

    public static native Metadata getMetadata(String path, boolean readPictures);

    public static native String[] getMetadataPropertyValues(String path, String propertyName);

    public static native Picture[] getPictures(String path);

    public static native boolean savePropertyMap(String path, HashMap<String, String[]> propertyMap);

    public static native boolean savePictures(String path, Picture[] pictures);
}
