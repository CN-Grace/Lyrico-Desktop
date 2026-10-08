package com.lonx.lyrico.data.song.tag

data class AudioTagReadOptions(
    val multiValueSeparator: String = "/",
    // Mutating operations must distinguish read failures from genuinely empty tags.
    val strict: Boolean = false
)
