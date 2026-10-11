package com.lonx.lyrico.utils

import com.lonx.lyrico.domain.poster.ArtistPosterFileNaming
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 往海报文件夹里写一张海报：文件名认人，替换旧海报时先写临时文件再动旧的。
 *
 * 全程用真的临时目录和真的字节 —— 这里没有可替换的抽象，`File` 就是被测对象。
 *
 * 两个不变量决定了这个测试的形状，它们都是从 Android 的 SAF 实现原样搬过来的：
 *
 * * **新文件一定是一份完整的图才去动旧文件。** 直接把 PNG 字节写进旧的 `.webp` 会先把它截断，
 *   写到一半失败就把用户原来的海报毁了。
 * * **旧文件删不掉时不改名。** 两个文件都留着（多一张图总比丢图好），返回 `null` 让界面提示没存成。
 *   造这个失败要靠真实文件锁：Windows 上另一个句柄打开着文件时 `File.delete()` 就会失败，
 *   这正是「删不掉」在真实使用里的样子（杀毒软件、别的看图程序、资源管理器预览）。
 */
class ArtistPosterFileWriterTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("lyrico-poster-writer").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String) = File(dir, name)

    private fun names() = dir.listFiles().orEmpty().map { it.name }.sorted()

    private suspend fun write(
        artist: String = "Aria",
        data: ByteArray = byteArrayOf(1, 2, 3),
        mime: String = "image/jpeg",
        folder: String? = dir.absolutePath,
    ) = ArtistPosterFileWriter.write(
        artistName = artist,
        data = data,
        mimeType = mime,
        posterFolder = folder,
    )

    // ------------------------------------------------------------------ tests

    @Test
    fun `a poster is written under the artist's name with the extension of its mime type`() =
        runBlocking<Unit> {
            // The picked file's own name is not passed in, deliberately: external posters are matched by
            // file name, so the name on disk has to be the artist, never what the user's file was called.
            val data = byteArrayOf(11, 22, 33, 44)

            val written = write(artist = "Aria", data = data, mime = "image/png")

            assertEquals("Aria.png", written)
            assertEquals(listOf("Aria.png"), names(), "nothing else is left behind")
            assertTrue(data.contentEquals(file("Aria.png").readBytes()), "the bytes are the bytes that were handed in")
        }

    @Test
    fun `an artist name a file name cannot hold is sanitized the way the app renames files`() =
        runBlocking<Unit> {
            // `AC/DC` cannot be a file name, so the app's own rule maps the slash to its full-width form --
            // which the matcher then folds back, so the poster is still recognized as this artist's.
            val written = write(artist = "AC/DC", mime = "image/jpeg")

            assertEquals(ArtistPosterFileNaming.stemFor("AC/DC")?.plus(".jpg"), written)
            assertEquals(listOf("AC／DC.jpg"), names())
        }

    @Test
    fun `an unknown mime type falls back to jpg, the way the shared naming rule decides it`() =
        runBlocking<Unit> {
            // `extensionFor` can also read an extension off a file name, but the writer has none to give it:
            // only the detected mime type is passed down. Android did the same, so this is parity, not a port
            // decision -- if the fallback is ever wrong it is wrong on both platforms.
            assertEquals("Aria.jpg", write(mime = "video/quicktime"))
        }

    @Test
    fun `replacing a poster of another format leaves exactly one file, under the new name`() =
        runBlocking<Unit> {
            // The normal path of "save a new image for this artist": there is an old `.webp` (say, written by
            // a previous save), and the new bytes are a PNG.
            file("Aria.webp").writeBytes(byteArrayOf(9, 9, 9))
            file("Nobody.webp").writeBytes(byteArrayOf(7, 7, 7))

            val written = write(artist = "Aria", data = byteArrayOf(5, 5), mime = "image/png")

            assertEquals("Aria.png", written)
            assertEquals(
                listOf("Aria.png", "Nobody.webp"),
                names(),
                "the artist's old poster is gone, the other artist's file is untouched, and no staged file survives",
            )
            assertTrue(byteArrayOf(5, 5).contentEquals(file("Aria.png").readBytes()))
        }

    @Test
    fun `a replacement found by a suffixed name is replaced too`() = runBlocking<Unit> {
        // `Aria_live.jpg` is the same artist's poster by the matcher's rank 2 rule, and it is the file the
        // page is showing, so it is the one a new save replaces.
        file("Aria_live.jpg").writeBytes(byteArrayOf(1))

        assertEquals("Aria.jpg", write(artist = "Aria", data = byteArrayOf(2), mime = "image/jpeg"))
        assertEquals(listOf("Aria.jpg"), names())
    }

    @Test
    fun `when the old poster cannot be deleted the new one is dropped and the old one survives`() =
        runBlocking<Unit> {
            val old = file("Aria.webp").apply { writeBytes(byteArrayOf(1, 1, 1, 1)) }

            // The premise is a Windows behavior: a file opened by another handle without share-delete cannot
            // be deleted. Assert it on a throwaway file first, so that on a platform where it does not hold
            // this test fails at the premise instead of passing while testing nothing.
            val probe = file("probe.txt").apply { writeText("probe") }
            FileInputStream(probe).use {
                assertFalse(probe.delete(), "the premise of this test is that an open file cannot be deleted")
            }
            assertTrue(probe.delete(), "and that it can be deleted once the handle is closed")

            val handle = FileInputStream(old)
            try {
                val written = write(artist = "Aria", data = byteArrayOf(2, 2), mime = "image/png")

                assertNull(written, "`null` is how the page learns the save did not land")
                assertEquals(listOf("Aria.webp"), names(), "no staged file and no half-written new poster is left")
                assertTrue(
                    byteArrayOf(1, 1, 1, 1).contentEquals(old.readBytes()),
                    "the poster the user already had is still a complete image",
                )
            } finally {
                handle.close()
            }

            // With the handle gone the same save succeeds, which is the retry the page's message asks for.
            assertEquals("Aria.png", write(artist = "Aria", data = byteArrayOf(2, 2), mime = "image/png"))
            assertEquals(listOf("Aria.png"), names())
        }

    @Test
    fun `nothing is written without a folder, without bytes, or without a usable artist name`() =
        runBlocking<Unit> {
            assertNull(write(folder = null), "no folder configured: nothing to write into")
            assertNull(write(data = ByteArray(0)), "an empty read (a failed picker result) is not a poster")
            assertNull(write(artist = "   ", mime = "image/jpeg"), "a blank name has no stem to use")
            assertNull(
                write(artist = "...", mime = "image/jpeg"),
                "a name left with nothing but trailing dots is a failed save, not a file called `...jpg`",
            )
            assertEquals(emptyList(), names(), "and in every one of those cases the folder stays untouched")
        }

    @Test
    fun `a folder that is not there is a failed save, not an exception`() = runBlocking<Unit> {
        val missing = File(dir, "gone/away")

        assertNull(write(folder = missing.absolutePath))
        assertFalse(missing.exists())
    }
}
