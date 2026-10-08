package com.lonx.audiotag.internal

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loads the native bridge library (`taglib.dll` on Windows).
 *
 * `System.loadLibrary("taglib")` alone is not enough for a desktop application: the library does not
 * live on `java.library.path` but beside the launcher (jpackage `app/` directory) or in the build
 * output while developing. The search order below is deliberately explicit so a packaging mistake
 * fails loudly with the directories it actually probed instead of an opaque
 * `UnsatisfiedLinkError`.
 */
public object NativeLibraryLoader {

    public const val TAGLIB: String = "taglib"

    /** Directory holding the native libraries, set by the launcher / installer. */
    public const val NATIVE_DIR_PROPERTY: String = "lyrico.native.dir"

    /** Same as [NATIVE_DIR_PROPERTY], for environments that cannot pass JVM properties. */
    public const val NATIVE_DIR_ENV: String = "LYRICO_NATIVE_DIR"

    private val loaded: MutableSet<String> = HashSet()

    /**
     * Loads [libraryName] if it has not been loaded yet.
     *
     * @param searchDirs directories probed before falling back to `java.library.path`.
     * @throws UnsatisfiedLinkError when the library cannot be found anywhere.
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    public fun load(
        libraryName: String = TAGLIB,
        searchDirs: List<Path> = defaultSearchDirs(),
    ) {
        if (!loaded.add(libraryName)) return

        val fileName = mappedLibraryFileName(libraryName)
        val probed = mutableListOf<Path>()

        for (dir in searchDirs) {
            val candidate = dir.resolve(fileName).toAbsolutePath().normalize()
            probed.add(candidate)
            if (Files.isRegularFile(candidate)) {
                System.load(candidate.toString())
                TagLog.i(libraryName, "loaded native library from $candidate")
                return
            }
        }

        // Last resort: whatever the JVM was told to search (java.library.path).
        try {
            System.loadLibrary(libraryName)
            TagLog.i(libraryName, "loaded native library via java.library.path")
            return
        } catch (_: UnsatisfiedLinkError) {
            // reported below with the full list of probed locations
        }

        loaded -= libraryName
        throw UnsatisfiedLinkError(
            "Unable to load native library '$fileName'. Probed: " +
                probed.joinToString(", ") + " and java.library.path=" +
                System.getProperty("java.library.path")
        )
    }

    /** Whether [libraryName] has already been loaded successfully. */
    @JvmStatic
    public fun isLoaded(libraryName: String = TAGLIB): Boolean = libraryName in loaded

    /** Directories searched by default, most specific first. */
    public fun defaultSearchDirs(): List<Path> {
        val dirs = mutableListOf<Path>()

        System.getProperty(NATIVE_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let {
            dirs.add(Paths.get(it))
        }
        System.getenv(NATIVE_DIR_ENV)?.takeIf { it.isNotBlank() }?.let { dirs.add(Paths.get(it)) }

        // jpackage layout: <app>/native next to <app>/app/*.jar
        dirs.add(Paths.get("native"))

        // Development layout: run from the repository, libraries in build/native/<platform>.
        val platformDir = "build/native/${platformTag()}"
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            dirs.add(dir.resolve(platformDir))
            dir = dir.parent
        }

        return dirs
    }

    private fun mappedLibraryFileName(libraryName: String): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "$libraryName.dll"
            os.contains("mac") -> "lib$libraryName.dylib"
            else -> "lib$libraryName.so"
        }
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
