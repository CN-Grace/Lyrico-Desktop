package com.lonx.lyrico.utils.coil

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.crossfade
import java.io.File

/**
 * Coil's process-wide loader for the desktop app.
 *
 * Android installed this through `App : SingletonImageLoader.Factory`, an Application-level hook that
 * has no desktop equivalent, so it is installed explicitly once during start-up instead. The custom
 * components are the ones the Android loader registered: the keyer that ties a cached cover to the
 * file's modification time, and the fetcher that reads embedded artwork (plus the artist's poster
 * folder) through TagLib. The disk cache lives under the app's own cache directory rather than
 * `Context.cacheDir`.
 */
fun installImageLoader(cacheDir: File) {
    SingletonImageLoader.setSafe { context: PlatformContext -> buildImageLoader(context, cacheDir) }
}

/**
 * The loader itself, without the process-wide singleton.
 *
 * Split out so a test can drive the exact components the app installs while still owning the loader
 * it exercises: `SingletonImageLoader` initialises once per process and would leak one test's cache
 * directory into every later one.
 */
internal fun buildImageLoader(context: PlatformContext, cacheDir: File): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(AudioCoverKeyer())
            add(AudioCoverFetcher.Factory())
        }
        .diskCache {
            DiskCache.Builder()
                // 磁盘缓存：最大 50 MB，目录为应用缓存目录
                .maxSizeBytes(50L * 1024 * 1024)
                .directory(cacheDir)
                .build()
        }
        .crossfade(true)
        .build()
