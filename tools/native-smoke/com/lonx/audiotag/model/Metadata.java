package com.lonx.audiotag.model;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Throwaway test double for the Kotlin {@code com.lonx.audiotag.model.Metadata}.
 *
 * <p>Only exists so the native-layer smoke harness can run without Gradle/Kotlin. The JNI layer
 * looks this class up by name in {@code JNI_OnLoad}, so the constructor signature below must stay
 * byte-for-byte identical to the Kotlin data class:
 *
 * <pre>(Ljava/util/HashMap;[Lcom/lonx/audiotag/model/Picture;Z)V</pre>
 */
public final class Metadata {
    public final HashMap<String, String[]> propertyMap;
    public final Picture[] pictures;
    public final boolean supportsTypedPictures;

    public Metadata(
            HashMap<String, String[]> propertyMap,
            Picture[] pictures,
            boolean supportsTypedPictures) {
        this.propertyMap = propertyMap;
        this.pictures = pictures;
        this.supportsTypedPictures = supportsTypedPictures;
    }

    @Override
    public String toString() {
        return "Metadata{props=" + propertyMap.size()
                + ", pictures=" + pictures.length
                + ", supportsTypedPictures=" + supportsTypedPictures
                + ", keys=" + propertyMap.keySet() + "}";
    }

    /** Convenience for the harness: flatten the map for readable assertion output. */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (String key : new java.util.TreeSet<>(propertyMap.keySet())) {
            sb.append("\n    ").append(key).append(" = ").append(Arrays.toString(propertyMap.get(key)));
        }
        return sb.toString();
    }
}
