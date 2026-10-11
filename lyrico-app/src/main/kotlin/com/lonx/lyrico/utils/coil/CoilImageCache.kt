package com.lonx.lyrico.utils.coil

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lonx.lyrico.utils.ImageCache

/**
 * The real [ImageCache]: the numbers come from Coil's own caches, not from counting files.
 *
 * Both facts matter for the settings screen's "clear cache":
 *
 * - **Size is `DiskCache.size`, the same number Coil trims against.** Walking the directory instead
 *   would include orphaned entries Coil is about to delete itself, so the screen would promise to free
 *   bytes that were never ours.
 * - **Clearing goes through `DiskCache.clear()`.** While the process is alive that journal is open, and
 *   on Windows an open file cannot be deleted — deleting the directory behind Coil's back leaves a
 *   half-written cache and a loader that keeps writing into it.
 *
 * The loader is resolved lazily, per call, from [SingletonImageLoader]. The application installs it at
 * startup ([installImageLoader]); a test that never installs one gets Coil's default loader rather than
 * a crash, and nothing here holds the instance across the app's lifetime.
 */
class CoilImageCache(
    private val context: PlatformContext = PlatformContext.INSTANCE,
) : ImageCache {

    private val loader: ImageLoader
        get() = SingletonImageLoader.get(context)

    override fun diskSizeBytes(): Long = loader.diskCache?.size ?: 0L

    override fun clear() {
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }
}
