package com.lonx.audiotag.model;

/**
 * Throwaway test double for the Kotlin {@code com.lonx.audiotag.model.AudioProperties}.
 *
 * <p>Constructor signature must match the JNI_OnLoad lookup exactly: {@code (IIII)V}.
 * Field order mirrors the Kotlin data class: length, bitrate, sampleRate, channels.
 */
public final class AudioProperties {
    public final int length;
    public final int bitrate;
    public final int sampleRate;
    public final int channels;

    public AudioProperties(int length, int bitrate, int sampleRate, int channels) {
        this.length = length;
        this.bitrate = bitrate;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    @Override
    public String toString() {
        return "AudioProperties{length=" + length + "ms, bitrate=" + bitrate
                + "kbps, sampleRate=" + sampleRate + "Hz, channels=" + channels + "}";
    }
}
