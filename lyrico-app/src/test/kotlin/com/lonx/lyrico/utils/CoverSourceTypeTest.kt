package com.lonx.lyrico.utils

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The classification the cover writers switch on: which of the four desktop sources a value is.
 *
 * The dropped Android arms are pinned here as `UNSUPPORTED` / `FILE_PATH`, because the producers
 * (picker, tag reader, crop result) must hand over paths and `ImageBitmap`s -- a `File` slipping
 * through would silently be treated as a path string by `toString()` on a type the writer does not
 * expect, so it is rejected instead.
 */
class CoverSourceTypeTest {

    @Test
    fun `tag bytes are a byte array source`() {
        assertEquals(CoverSourceType.BYTE_ARRAY, getCoverSourceType(ByteArray(3)))
        assertEquals(CoverSourceType.BYTE_ARRAY, getCoverSourceType(byteArrayOf()))
    }

    @Test
    fun `a rendered image is an image bitmap source`() {
        assertEquals(CoverSourceType.IMAGE_BITMAP, getCoverSourceType(ImageBitmap(2, 2)))
    }

    @Test
    fun `http and https strings are network urls`() {
        assertEquals(CoverSourceType.NETWORK_URL, getCoverSourceType("https://example.com/cover.jpg"))
        assertEquals(CoverSourceType.NETWORK_URL, getCoverSourceType("http://example.com/cover.jpg"))
        assertEquals(CoverSourceType.NETWORK_URL, getCoverSourceType("HTTPS://example.com/cover.jpg"))
        assertEquals(CoverSourceType.NETWORK_URL, getCoverSourceType("  https://example.com/cover.jpg  "))
    }

    @Test
    fun `everything else that is a string is a file path`() {
        assertEquals(CoverSourceType.FILE_PATH, getCoverSourceType("C:\\Music\\Album\\cover.jpg"))
        assertEquals(CoverSourceType.FILE_PATH, getCoverSourceType("/home/user/cover.jpg"))
        assertEquals(CoverSourceType.FILE_PATH, getCoverSourceType("  C:\\cover.jpg  "))
        // 桌面端没有任何地方会再产出 file:// 字符串（songs.uri 存的是绝对路径），所以它只可能是
        // 一个打不开的路径；这里把它归到 FILE_PATH，由写盘那一步按普通的文件错误上报。
        assertEquals(CoverSourceType.FILE_PATH, getCoverSourceType("file:///C:/cover.jpg"))
    }

    @Test
    fun `anything else is unsupported`() {
        assertEquals(CoverSourceType.UNSUPPORTED, getCoverSourceType(null))
        assertEquals(CoverSourceType.UNSUPPORTED, getCoverSourceType(42))
        assertEquals(CoverSourceType.UNSUPPORTED, getCoverSourceType(File("C:\\cover.jpg")))
    }
}
