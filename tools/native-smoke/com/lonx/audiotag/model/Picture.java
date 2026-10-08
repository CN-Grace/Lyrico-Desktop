package com.lonx.audiotag.model;

import java.util.Arrays;

/**
 * Throwaway test double for the Kotlin {@code com.lonx.audiotag.model.Picture}.
 *
 * <p>The JNI layer uses the constructor {@code ([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V}
 * and the four getters {@code getData()}, {@code getDescription()}, {@code getPictureType()},
 * {@code getMimeType()} — the field order below must mirror the Kotlin data class exactly, because
 * the native code passes arguments positionally.
 */
public final class Picture {
    private final byte[] data;
    private final String description;
    private final String pictureType;
    private final String mimeType;

    public Picture(byte[] data, String description, String pictureType, String mimeType) {
        this.data = data;
        this.description = description;
        this.pictureType = pictureType;
        this.mimeType = mimeType;
    }

    public byte[] getData() {
        return data;
    }

    public String getDescription() {
        return description;
    }

    public String getPictureType() {
        return pictureType;
    }

    public String getMimeType() {
        return mimeType;
    }

    @Override
    public String toString() {
        return "Picture{bytes=" + (data == null ? -1 : data.length)
                + ", description='" + description + "'"
                + ", pictureType='" + pictureType + "'"
                + ", mimeType='" + mimeType + "'}";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Picture)) {
            return false;
        }
        Picture o = (Picture) other;
        return Arrays.equals(data, o.data)
                && java.util.Objects.equals(description, o.description)
                && java.util.Objects.equals(pictureType, o.pictureType)
                && java.util.Objects.equals(mimeType, o.mimeType);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
