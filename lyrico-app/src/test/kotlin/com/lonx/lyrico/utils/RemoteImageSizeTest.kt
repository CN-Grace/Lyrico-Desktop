package com.lonx.lyrico.utils

import com.lonx.lyrico.data.support.LocalGitHubServer
import com.lonx.lyrico.support.PlatformLogCapture
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [readRemoteImageSize] over real HTTP.
 *
 * The function exists to replace Android's `BitmapFactory.inJustDecodeBounds`, which read an image's
 * *header* to learn its pixel size without decoding it -- the cover-search grid uses the size to lay
 * out a placeholder of the right shape before the image itself arrives. The JDK equivalent is
 * `ImageIO`'s reader, and the interesting parts are not the happy path but the two ways a URL is
 * useless: it answers with something that is not an image, or it does not answer at all.
 *
 * The server is real (a loopback `HttpServer`, shared with the rest of the suite as
 * [LocalGitHubServer]); so is the PNG, which is encoded by the same `ImageIO` machinery that decodes
 * it -- so a passing test means the header parse actually worked, not that a stub returned `37 to 19`.
 */
class RemoteImageSizeTest {

    private val server = LocalGitHubServer().start()

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `reads the dimensions of a real png over http`() = runTest {
        server.respondBytes("/cover.png", png(width = 37, height = 19), contentType = "image/png")

        assertEquals(
            37 to 19,
            readRemoteImageSize(server.url("/cover.png")),
            "the size must come from the served image header, not from a default",
        )
    }

    @Test
    fun `jpeg and gif dimensions are read too`() = runTest {
        server.respondBytes("/cover.jpg", image("jpg", 120, 80), contentType = "image/jpeg")
        server.respondBytes("/cover.gif", image("gif", 64, 32), contentType = "image/gif")

        assertEquals(120 to 80, readRemoteImageSize(server.url("/cover.jpg")))
        assertEquals(64 to 32, readRemoteImageSize(server.url("/cover.gif")))
    }

    @Test
    fun `a body that is not an image yields null and is logged`() = runTest {
        // What a rate-limited or erroring cover host actually returns: a JSON or HTML document with a
        // 200. `BitmapFactory` returned 0/0 here; the port must not invent a size.
        server.respond("/not-an-image.png", """{"message":"rate limited"}""")

        val log = PlatformLogCapture().install()
        try {
            assertNull(readRemoteImageSize(server.url("/not-an-image.png")))
            assertTrue(
                log.warnings.any { it.contains("无法读取图片尺寸") },
                "the failure must be explained, got: ${log.lines}"
            )
        } finally {
            log.restore()
        }
    }

    @Test
    fun `a 404 yields null`() = runTest {
        // Unregistered paths answer 404 with a JSON body (see `LocalGitHubServer`), which is exactly the
        // shape of a cover URL that has gone stale.
        assertNull(readRemoteImageSize(server.url("/missing.png")))
    }

    @Test
    fun `an unreachable host yields null instead of throwing`() = runTest {
        val deadPort = server.port
        server.stop()

        // Port is now closed, so the connection is refused immediately -- no need to wait out the 5s
        // timeout. What matters is that a refused connection is a null, not a `ConnectException` thrown
        // into the caller's coroutine: the caller is a grid item's `LaunchedEffect`.
        assertNull(readRemoteImageSize("http://127.0.0.1:$deadPort/cover.png"))
    }

    private fun LocalGitHubServer.url(path: String) = "http://127.0.0.1:$port$path"

    private fun png(width: Int, height: Int) = image("png", width, height)

    private fun image(format: String, width: Int, height: Int): ByteArray {
        val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream()
        ImageIO.write(canvas, format, bytes)
        return bytes.toByteArray()
    }
}
