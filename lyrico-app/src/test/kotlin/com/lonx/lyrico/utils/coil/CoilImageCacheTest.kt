package com.lonx.lyrico.utils.coil

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lonx.lyrico.utils.getFolderSize
import java.io.File
import java.nio.file.Files
import okio.FileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The one thing [CoilImageCache] exists for: reporting and clearing the cover cache *through Coil*.
 *
 * The assertions are chosen so that the two easy wrong implementations fail:
 *
 * - **size**: a stray file is placed in the cache directory that Coil knows nothing about. Walking the
 *   directory (the Android version's approach, and the obvious desktop one) would count it; Coil's ledger
 *   does not, and that difference is the whole point — the settings screen promises to free the bytes it
 *   reports.
 * - **clearing**: `DiskCache.clear()` is used instead of deleting the directory, because Coil's journal
 *   file is open for as long as the process lives and Windows refuses to delete an open file. The test
 *   proves the cache is *still usable* afterwards, which a directory deletion that raced Coil would not be.
 *
 * The loader under test is built by the app's own [buildImageLoader] and installed as the process-wide
 * singleton (that is what [CoilImageCache] reads), then reset in teardown so no later test inherits either
 * the loader or its temp directory — `SingletonImageLoader` initialises once per process.
 */
class CoilImageCacheTest {

    private lateinit var cacheDir: File
    private lateinit var loader: ImageLoader
    private lateinit var cache: CoilImageCache

    @BeforeTest
    fun setUp() {
        cacheDir = Files.createTempDirectory("lyrico-image-cache").toFile()
        loader = buildImageLoader(PlatformContext.INSTANCE, cacheDir)
        // A previous test may already have caused Coil's default singleton to be created, and setting one
        // over an existing singleton throws.
        SingletonImageLoader.reset()
        SingletonImageLoader.setUnsafe(loader)
        cache = CoilImageCache()
    }

    @AfterTest
    fun tearDown() {
        // Shutdown first: the journal is an open file, and on Windows the directory cannot be deleted
        // until it is closed.
        runCatching { loader.diskCache?.shutdown() }
        runCatching { SingletonImageLoader.reset() }
        cacheDir.deleteRecursively()
    }

    /** Writes a real entry into Coil's disk cache, the way one of its fetchers would. */
    private fun writeEntry(key: String, sizeBytes: Int) {
        val diskCache = assertNotNull(loader.diskCache, "the test loader has no disk cache")
        val editor = assertNotNull(diskCache.openEditor(key), "another editor already holds $key")
        diskCache.fileSystem.write(editor.data) { write(ByteArray(sizeBytes) { 7 }) }
        editor.commit()
    }

    @Test
    fun `an empty cache reports zero`() {
        assertEquals(0L, cache.diskSizeBytes())
    }

    @Test
    fun `a written entry is counted`() {
        writeEntry("cover-1", sizeBytes = 4096)

        val size = cache.diskSizeBytes()
        assertTrue(size >= 4096L, "Coil's own accounting did not include the entry: $size")
        assertEquals(
            assertNotNull(loader.diskCache).size,
            size,
            "the reported size disagrees with the loader's own ledger",
        )
    }

    @Test
    fun `the size is Coil's ledger, not the size of the directory`() {
        writeEntry("cover-1", sizeBytes = 4096)
        val coilSize = cache.diskSizeBytes()

        val stray = File(cacheDir, "stray.bin")
        stray.writeBytes(ByteArray(65_536))

        assertEquals(coilSize, cache.diskSizeBytes(), "a file Coil never wrote was counted as cache")
        assertTrue(
            cacheDir.getFolderSize() > coilSize,
            "the fixture does not discriminate: the directory is not bigger than Coil's ledger",
        )
    }

    @Test
    fun `clearing empties the cache and leaves the loader usable`() {
        writeEntry("cover-1", sizeBytes = 4096)
        assertTrue(cache.diskSizeBytes() > 0L, "the entry was not written, so clearing would prove nothing")

        cache.clear()

        assertEquals(0L, cache.diskSizeBytes(), "clearing left entries behind")
        // Still usable: the cache was cleared through Coil, not deleted out from under it.
        writeEntry("cover-2", sizeBytes = 2048)
        assertTrue(cache.diskSizeBytes() >= 2048L, "the cache no longer accepts entries after a clear")
    }

    @Test
    fun `the file system the cache reports is the loader's own directory`() {
        // Guards the assumption in the size test above: if the loader's cache lived somewhere else, the
        // stray file would not be a fair comparison at all.
        val diskCache = assertNotNull(loader.diskCache)
        assertEquals(cacheDir.canonicalFile, File(diskCache.directory.toString()).canonicalFile)
        assertEquals(FileSystem.SYSTEM, diskCache.fileSystem)
    }
}
