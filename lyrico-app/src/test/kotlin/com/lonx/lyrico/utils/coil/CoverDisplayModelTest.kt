package com.lonx.lyrico.utils.coil

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Which cover *sources* the app's own image loader can draw, checked against the real loader rather
 * than assumed.
 *
 * `CoverSourceImage` has one branch in it -- an [ImageBitmap] is drawn directly, everything else goes
 * through Coil -- and this class is the evidence for why. Coil's registry resolves a `ByteArray` and an
 * absolute path out of the box, but it has no model for a Compose [ImageBitmap] at all, so handing one
 * to `AsyncImage` produces an [ErrorResult] and an empty cover. That is a fact worth pinning down,
 * because the natural "simplification" of deleting the branch compiles, runs, and quietly breaks the
 * one thing the editor is for: showing the picture the user just picked or cropped.
 *
 * The `file://` case is here for the same reason from the other side: `CoverSourceType` never produces
 * one (desktop sources are paths or bytes), and if a future change starts producing them, this test
 * says what happens instead of leaving it to be discovered in the UI.
 */
class CoverDisplayModelTest {

    private lateinit var workDir: Path

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("lyrico-display-model-")
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    @Test
    fun `an embedded picture's bytes are decoded to their real size`() {
        val result = load(pngBytes(width = 37, height = 23))

        val success = assertIs<SuccessResult>(result, "a ByteArray cover must load: $result")
        assertEquals(37, assertNotNull(success.image).width)
        assertEquals(23, success.image.height)
    }

    @Test
    fun `an absolute path is decoded to its real size`() {
        val file = workDir.resolve("cover.png").toFile().apply { writeBytes(pngBytes(37, 23)) }

        val result = load(file.absolutePath)

        val success = assertIs<SuccessResult>(result, "a path cover must load: $result")
        assertEquals(37, assertNotNull(success.image).width)
    }

    @Test
    fun `an image bitmap is not a model the loader can resolve`() {
        val bitmap: ImageBitmap = ImageIO.read(ByteArrayInputStream(pngBytes(37, 23))).toComposeImageBitmap()

        val result = load(bitmap)

        assertIs<ErrorResult>(
            result,
            "Coil learned to draw ImageBitmaps: the branch in CoverSourceImage can go away now",
        )
    }

    @Test
    fun `a file uri is not a model the loader can resolve`() {
        val file = workDir.resolve("cover.png").toFile().apply { writeBytes(pngBytes(37, 23)) }

        val result = load(file.toURI().toString())

        assertIs<ErrorResult>(
            result,
            "Coil learned to draw file:// URIs: sources could start carrying them, but until then a path is the input",
        )
    }

    private fun load(data: Any?): Any = runBlocking {
        buildImageLoader(
            context = PlatformContext.INSTANCE,
            cacheDir = workDir.resolve("cache").also { Files.createDirectories(it) }.toFile(),
        ).execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(data).build())
    }

    private fun pngBytes(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { out ->
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out)
    }.toByteArray()
}
