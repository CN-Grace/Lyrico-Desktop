package com.lonx.lyrico.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Raised when the ffmpeg sidecar is not on this machine, carrying every path that was probed. */
class FfmpegUnavailableException(
    val probed: List<Path>,
) : Exception(
    "ffmpeg sidecar not found. Probed: " + probed.joinToString(", ") +
        ". Run scripts/fetch-ffmpeg.ps1, or point " + FfmpegSidecar.DIR_PROPERTY + " at a directory " +
        "containing " + FfmpegSidecar.executableName() + "."
)

/**
 * Finds the bundled `ffmpeg.exe` that ReplayGain decoding runs on (see `docs/third-party/ffmpeg-lgpl.md`).
 *
 * This is the process-shaped sibling of `NativeLibraryLoader`: the sidecar is not on `PATH` — Lyrico
 * never uses a system ffmpeg, because then the version and licence of the binary doing the measuring
 * would depend on the machine — so the directory has to be found explicitly. It lives beside the
 * launcher in a packaged build and under `build/` during development, neither of which is a place the
 * JVM looks by itself.
 *
 * The search order is deliberate and the failure message lists the paths actually probed, so a
 * packaging mistake fails with an address instead of "ffmpeg not found".
 */
class FfmpegSidecar(
    private val searchDirs: List<Path> = defaultSearchDirs(),
) {

    /** The binary to look for inside each directory. */
    fun locate(): Path {
        val probed = probedPaths()
        return probed.firstOrNull { Files.isRegularFile(it) }
            ?: throw FfmpegUnavailableException(probed)
    }

    /** Whether [locate] would succeed, for callers that want to warn before starting work. */
    fun isAvailable(): Boolean = probedPaths().any { Files.isRegularFile(it) }

    /** Every path this instance would accept, in search order. */
    fun probedPaths(): List<Path> =
        searchDirs.map { it.resolve(executableName()).toAbsolutePath().normalize() }

    companion object {

        /** Directory holding the sidecar, set by the launcher / installer. */
        const val DIR_PROPERTY: String = "lyrico.ffmpeg.dir"

        /** Same as [DIR_PROPERTY], for environments that cannot pass JVM properties. */
        const val DIR_ENV: String = "LYRICO_FFMPEG_DIR"

        /** Name of the executable, which is platform-mapped like `NativeLibraryLoader`'s libraries. */
        fun executableName(): String =
            if (System.getProperty("os.name").lowercase().contains("win")) "ffmpeg.exe" else "ffmpeg"

        /** Directories searched by default, most specific first. */
        fun defaultSearchDirs(): List<Path> {
            val dirs = mutableListOf<Path>()

            System.getProperty(DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let {
                dirs.add(Paths.get(it))
            }
            System.getenv(DIR_ENV)?.takeIf { it.isNotBlank() }?.let { dirs.add(Paths.get(it)) }

            val platformDir = platformTag()
            var dir: Path? = Paths.get("").toAbsolutePath()
            while (dir != null) {
                // Development layout: the fetch script writes into build/ffmpeg/<platform>.
                dirs.add(dir.resolve("build/ffmpeg").resolve(platformDir))
                // Packaged layout: the installer copies that directory next to the launcher,
                // keeping the platform tag (P6 copies build/ffmpeg verbatim).
                dirs.add(dir.resolve("ffmpeg").resolve(platformDir))
                // Packaged layout without the tag, for an installer that flattens it.
                dirs.add(dir.resolve("ffmpeg"))
                dir = dir.parent
            }

            return dirs
        }

        private fun platformTag(): String {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val osTag = when {
                os.contains("win") -> "windows"
                os.contains("mac") -> "macos"
                else -> "linux"
            }
            val archTag = when (arch) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "arm64"
                else -> arch
            }
            return "$osTag-$archTag"
        }
    }
}
