package com.lonx.lyrico.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the ffmpeg locator.
 *
 * Two properties matter beyond "it finds the file": an explicit directory list must be honoured
 * verbatim (so a test or a packaging step can point the locator somewhere on purpose), and a miss
 * must name every path it tried — the whole reason this class exists instead of a `PATH` lookup is
 * that "ffmpeg not found" is not actionable while "probed …\\build\\ffmpeg\\windows-x64\\ffmpeg.exe"
 * is.
 */
class FfmpegSidecarTest {

    @Test
    fun `finds the bundled executable in the development layout`() {
        val sidecar = FfmpegSidecar()
        assumeTrue(
            "ffmpeg sidecar not fetched; run scripts/fetch-ffmpeg.ps1",
            sidecar.isAvailable(),
        )

        val located = sidecar.locate()

        assertTrue(located.toString(), Files.isRegularFile(located))
        assertTrue(located.toString(), located.fileName.toString().startsWith("ffmpeg"))
        assertTrue(
            "the development layout puts the sidecar under build/ffmpeg",
            located.toString().replace('\\', '/').contains("/build/ffmpeg/"),
        )
    }

    @Test
    fun `probes only the directories it was given`() {
        val first = Files.createTempDirectory("lyrico-sidecar-first")
        val second = Files.createTempDirectory("lyrico-sidecar-second")

        val probed = FfmpegSidecar(listOf(first, second)).probedPaths()

        assertEquals(
            listOf(
                first.resolve(FfmpegSidecar.executableName()).toAbsolutePath().normalize(),
                second.resolve(FfmpegSidecar.executableName()).toAbsolutePath().normalize(),
            ),
            probed,
        )
    }

    @Test
    fun `reports every probed path when the executable is missing`() {
        val missing = Files.createTempDirectory("lyrico-sidecar-missing")
        val sidecar = FfmpegSidecar(listOf(missing))

        assertFalse(sidecar.isAvailable())
        val error = assertThrows(FfmpegUnavailableException::class.java) { sidecar.locate() }

        assertEquals(1, error.probed.size)
        assertTrue(error.message!!, error.message!!.contains(missing.resolve(FfmpegSidecar.executableName()).toString()))
        assertTrue(
            "the message has to say how to fix it",
            error.message!!.contains("fetch-ffmpeg.ps1"),
        )
    }

    @Test
    fun `finds a sidecar named by the launch property before anything else`() {
        val override = Files.createTempDirectory("lyrico-sidecar-override")
        Files.createFile(override.resolve(FfmpegSidecar.executableName()))
        val previous = System.getProperty(FfmpegSidecar.DIR_PROPERTY)
        try {
            System.setProperty(FfmpegSidecar.DIR_PROPERTY, override.toString())

            // Asserting on the search order rather than on locate(): this test must not leave a
            // window in which another test's default locator picks up a fake executable.
            assertEquals(
                override.resolve(FfmpegSidecar.executableName()).toAbsolutePath().normalize(),
                FfmpegSidecar.defaultSearchDirs().first().resolve(FfmpegSidecar.executableName()).toAbsolutePath().normalize(),
            )
        } finally {
            if (previous == null) {
                System.clearProperty(FfmpegSidecar.DIR_PROPERTY)
            } else {
                System.setProperty(FfmpegSidecar.DIR_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `prefers an explicit directory list over the launch property`() {
        val explicit = Files.createTempDirectory("lyrico-sidecar-explicit")
        val redirect = Files.createTempDirectory("lyrico-sidecar-redirect")
        Files.createFile(redirect.resolve(FfmpegSidecar.executableName()))
        val previous = System.getProperty(FfmpegSidecar.DIR_PROPERTY)
        try {
            System.setProperty(FfmpegSidecar.DIR_PROPERTY, redirect.toString())

            val probed = FfmpegSidecar(listOf(explicit)).probedPaths()

            assertEquals(1, probed.size)
            assertTrue(probed.first().startsWith(explicit.toAbsolutePath().normalize()))
        } finally {
            if (previous == null) {
                System.clearProperty(FfmpegSidecar.DIR_PROPERTY)
            } else {
                System.setProperty(FfmpegSidecar.DIR_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `walks up from the working directory through both packaged layouts`() {
        val probed = FfmpegSidecar.defaultSearchDirs().map { it.toString().replace('\\', '/') }
        val workingDirectory = Path.of("").toAbsolutePath().normalize().toString().replace('\\', '/')

        assumeTrue("working directory is not under a repository checkout", probed.any { it.startsWith(workingDirectory) })
        assertTrue(
            "expected the development layout to be probed from $workingDirectory: $probed",
            probed.any { it.endsWith("/build/ffmpeg/windows-x64") || it.endsWith("/build/ffmpeg/macos-x64") || it.endsWith("/build/ffmpeg/linux-x64") },
        )
    }
}
