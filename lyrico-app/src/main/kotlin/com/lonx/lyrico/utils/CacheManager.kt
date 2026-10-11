package com.lonx.lyrico.utils

import com.lonx.lyrico.data.model.cache.CacheCategory
import com.lonx.lyrico.platform.AppDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图片加载器自己的缓存，由 DI 接到 Coil 上。
 *
 * 单独抽出来是因为「测量」和「清理」要问的东西不一样：磁盘上占了多少字节，看目录最准；而清理
 * 必须走 Coil 的 [coil3.disk.DiskCache.clear]（它会同步自己的日志），在 Windows 上直接删目录
 * 反而会失败 —— Coil 进程存活期间日志文件是打开的，删不掉。
 */
interface ImageCache {

    /** 磁盘缓存现在占用的字节数。 */
    fun diskSizeBytes(): Long

    /** 清空磁盘缓存与内存缓存。 */
    fun clear()

    companion object {
        /** 没有接 Coil 时用的空实现：报告 0，清理不做任何事。 */
        val NoOp: ImageCache = object : ImageCache {
            override fun diskSizeBytes(): Long = 0L
            override fun clear() = Unit
        }
    }
}

/**
 * 可再生数据的测量与清理，供设置页的「清理缓存」用。
 *
 * Android 版拿 `Context` 找缓存目录、按文件名里有没有 `http` / `network` 猜类目；桌面版两个都
 * 换掉了：
 *
 * - **目录来自 [AppDirectories]**，不再有 `Context`；`Context.externalCacheDir` 那一类目跟着
 *   取消（见 `CacheCategory`）。
 * - **类目按真实目录划分**，不再靠文件名猜：图片是 Coil 的磁盘缓存目录、网络是 OkHttp 的
 *   `http_cache`、其余都算 OTHER。同一个目录不会被两类同时统计。
 *
 * [directories] 只用来定位网络与其它缓存；图片缓存的字节数与清理都走 [imageCache]，因为那份缓存
 * 是 Coil 的账本，清理也要经过它才安全。三个类目量的是同一件事：**这次清理能腾出多少字节**，
 * 所以图片报的是 Coil 记账的大小，而不是它目录里连残留文件一起数出来的大小。
 */
class CacheManager(
    private val directories: AppDirectories,
    private val imageCache: ImageCache = ImageCache.NoOp,
) {

    /**
     * 获取按类目分类的缓存大小。
     * @return Map<类目, 字节大小>
     */
    suspend fun getCategorizedCacheSize(): Map<CacheCategory, Long> =
        withContext(Dispatchers.IO) {
            val sizes = mutableMapOf<CacheCategory, Long>()
            sizes[CacheCategory.IMAGE] = imageCache.diskSizeBytes()
            sizes[CacheCategory.NETWORK] = directories.httpCacheDir.getFolderSize()
            sizes[CacheCategory.OTHER] = otherCacheFiles().sumOf { it.getFolderSize() }
            sizes
        }

    /**
     * 总大小
     */
    fun getTotalSize(map: Map<CacheCategory, Long>): Long = map.values.sum()

    /**
     * 清理指定类目
     */
    suspend fun clearCacheByCategory(category: CacheCategory) =
        withContext(Dispatchers.IO) {
            when (category) {
                CacheCategory.IMAGE -> imageCache.clear()

                CacheCategory.NETWORK -> {
                    directories.httpCacheDir.deleteRecursively()
                    directories.prepare()
                }

                CacheCategory.OTHER -> {
                    otherCacheFiles().forEach { it.deleteRecursively() }
                    directories.prepare()
                }
            }
        }

    /**
     * 清理全部
     *
     * 图片缓存只通过 [ImageCache.clear] 清空，不删它的目录：在 Windows 上进程打开着的文件删不掉，
     * 而 Coil 的磁盘缓存日志正是这样一个文件，绕过它去删目录只会留下半个缓存。
     */
    suspend fun clearAllCache() =
        withContext(Dispatchers.IO) {
            imageCache.clear()
            directories.httpCacheDir.deleteRecursively()
            otherCacheFiles().forEach { it.deleteRecursively() }
            directories.prepare()
        }

    /**
     * [AppDirectories.cacheDir] 下除图片与网络缓存以外的文件和目录。
     *
     * 每次现算，不缓存：清理之后目录内容会变，缓存列表会让「清理」看起来没生效。
     */
    private fun otherCacheFiles(): List<File> {
        val image = directories.coverCacheDir.absolutePath
        val network = directories.httpCacheDir.absolutePath
        return directories.cacheDir.listFiles()
            ?.filter { it.absolutePath != image && it.absolutePath != network }
            .orEmpty()
    }
}

/**
 * 递归获取文件夹大小（扩展函数）
 */
fun File.getFolderSize(): Long {
    if (!this.exists()) return 0L
    if (this.isFile) return this.length()

    return this.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
}
