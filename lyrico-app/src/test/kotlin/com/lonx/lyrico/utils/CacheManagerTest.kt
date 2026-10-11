package com.lonx.lyrico.utils

import com.lonx.lyrico.data.model.cache.CacheCategory
import com.lonx.lyrico.platform.AppDirectories
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 「清理缓存」量到的和清掉的，都落在 `AppDirectories` 的真实目录上。
 *
 * 用的是真临时目录和真字节：这里的被测对象就是文件系统，没有可替换的抽象。唯一的替身是
 * [ImageCache]（Coil 的缓存），用一个记账用的假实现，这样「图片类目报 Coil 的账本而不是目录大小」
 * 这件事能被钉住。
 *
 * Android 那套「按文件名里有没有 http / network 猜类目」的规则换成了按真实目录划分，所以下面几条
 * 测试主要防的是回归到猜名字：
 *
 * * 图片缓存目录虽然也在 `cacheDir` 下，但绝不能被算进 OTHER（否则清理 OTHER 会去删 Coil 正在用的
 *   目录）。
 * * 分类的字节数必须两两不重叠，总和等于三类之和。
 */
class CacheManagerTest {

    private lateinit var root: File
    private lateinit var directories: AppDirectories
    private lateinit var imageCache: FakeImageCache
    private lateinit var cacheManager: CacheManager

    private class FakeImageCache(var sizeBytes: Long = 0L) : ImageCache {
        var clearCount: Int = 0
            private set

        override fun diskSizeBytes(): Long = sizeBytes

        override fun clear() {
            clearCount++
            sizeBytes = 0L
        }
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("lyrico-cache-manager").toFile()
        directories = AppDirectories(root, isPortable = true).prepare()
        imageCache = FakeImageCache()
        cacheManager = CacheManager(directories, imageCache)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fileIn(dir: File, name: String, bytes: Int): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(ByteArray(bytes)) }
    }

    @Test
    fun `每个类目只统计自己的目录`() = runBlocking {
        imageCache.sizeBytes = 200
        fileIn(directories.httpCacheDir, "responses.bin", 100)
        fileIn(directories.pluginCacheDir, "plugin.bin", 300)
        fileIn(directories.cacheDir, "stray.bin", 50)

        val sizes = cacheManager.getCategorizedCacheSize()

        assertEquals(200L, sizes[CacheCategory.IMAGE])
        assertEquals(100L, sizes[CacheCategory.NETWORK])
        assertEquals(
            350L,
            sizes[CacheCategory.OTHER],
            "OTHER 是 cacheDir 里除图片与网络缓存以外的全部内容（这里 300 的插件缓存 + 50 的散落文件）",
        )
        assertEquals(650L, cacheManager.getTotalSize(sizes))
    }

    @Test
    fun `图片缓存目录不算进其它缓存`() = runBlocking {
        imageCache.sizeBytes = 7
        val cover = fileIn(directories.coverCacheDir, "entry.bin", 900)

        val sizes = cacheManager.getCategorizedCacheSize()

        assertEquals(0L, sizes[CacheCategory.OTHER])
        assertEquals(
            7L,
            sizes[CacheCategory.IMAGE],
            "图片类目报 Coil 记账的字节数（也就是清理能腾出来的量），不是目录里数出来的 900",
        )
        assertTrue(cover.exists())
    }

    @Test
    fun `清理网络缓存只动网络目录`() = runBlocking {
        val http = fileIn(directories.httpCacheDir, "responses.bin", 10)
        val plugin = fileIn(directories.pluginCacheDir, "plugin.bin", 10)
        val cover = fileIn(directories.coverCacheDir, "entry.bin", 10)

        cacheManager.clearCacheByCategory(CacheCategory.NETWORK)

        assertFalse(http.exists())
        assertTrue(plugin.exists(), "清理网络缓存不该碰到插件缓存")
        assertTrue(cover.exists(), "清理网络缓存不该碰到图片缓存")
        assertEquals(0, imageCache.clearCount, "Coil 的内存缓存与这一类比目无关")
    }

    @Test
    fun `清理其它缓存不动图片与网络缓存`() = runBlocking {
        val http = fileIn(directories.httpCacheDir, "responses.bin", 10)
        val plugin = fileIn(directories.pluginCacheDir, "plugin.bin", 10)
        fileIn(directories.cacheDir, "stray.bin", 10)
        val cover = fileIn(directories.coverCacheDir, "entry.bin", 10)

        cacheManager.clearCacheByCategory(CacheCategory.OTHER)

        assertTrue(http.exists())
        assertTrue(cover.exists(), "图片缓存目录是 Coil 的，删它会让 Coil 的日志指向不存在的文件")
        assertFalse(plugin.exists())
        assertFalse(File(directories.cacheDir, "stray.bin").exists())
    }

    @Test
    fun `清理全部时图片缓存交给 Coil 而不是删目录`() = runBlocking {
        imageCache.sizeBytes = 500
        val cover = fileIn(directories.coverCacheDir, "entry.bin", 10)
        val http = fileIn(directories.httpCacheDir, "responses.bin", 10)
        val plugin = fileIn(directories.pluginCacheDir, "plugin.bin", 10)

        cacheManager.clearAllCache()

        assertEquals(1, imageCache.clearCount)
        assertTrue(cover.exists(), "图片缓存目录不删：Windows 上 Coil 打开着的日志删不掉，绕过它删只会留下半个缓存")
        assertFalse(http.exists())
        assertFalse(plugin.exists())
    }

    @Test
    fun `清理之后目录骨架还在`() = runBlocking {
        cacheManager.clearAllCache()

        assertTrue(directories.cacheDir.isDirectory)
        assertTrue(
            directories.httpCacheDir.isDirectory,
            "OkHttp 与插件都按路径打开这些目录，清理不能把它们留成不存在的路径",
        )
        assertTrue(directories.coverCacheDir.isDirectory)
        assertTrue(directories.pluginCacheDir.isDirectory)
    }

    @Test
    fun `目录不存在时算 0 而不是抛异常`() = runBlocking {
        val empty = AppDirectories(Files.createTempDirectory("lyrico-cache-empty").toFile(), isPortable = true)
        val sizes = CacheManager(empty, imageCache).getCategorizedCacheSize()

        assertEquals(0L, sizes[CacheCategory.IMAGE])
        assertEquals(0L, sizes[CacheCategory.NETWORK])
        assertEquals(0L, sizes[CacheCategory.OTHER])
        assertEquals(0L, File(empty.root, "never-created").getFolderSize())

        assertTrue(empty.root.deleteRecursively())
    }
}
