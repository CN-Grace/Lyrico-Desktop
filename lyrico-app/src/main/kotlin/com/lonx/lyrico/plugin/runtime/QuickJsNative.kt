package com.lonx.lyrico.plugin.runtime

import com.lonx.audiotag.internal.NativeLibraryLoader

/**
 * JNI bridge to the bundled QuickJS engine (`quickjs-ng.dll`).
 *
 * Ported from Android with two desktop changes:
 *
 * - `System.loadLibrary("quickjs-ng")` is replaced by [NativeLibraryLoader], which probes the
 *   launcher/`lyrico.native.dir`/`build/native/<platform>` locations and reports every probed path
 *   when it fails. The library is not on `java.library.path` in either the development or the
 *   packaged layout, so a bare `loadLibrary` call would fail with an opaque `UnsatisfiedLinkError`.
 * - `@androidx.annotation.Keep` is dropped: it only exists to survive R8 shrinking, which the desktop
 *   build has no equivalent of. JNI resolves these methods by name and signature, so the annotation
 *   was never load-bearing at runtime.
 */
object QuickJsNative {
    init {
        NativeLibraryLoader.load(QUICKJS_NG)
    }

    /** Name of the bundled engine; the loader maps it to `quickjs-ng.dll` on Windows. */
    const val QUICKJS_NG: String = "quickjs-ng"

    external fun createRuntime(
        memoryLimitBytes: Long,
        stackSizeBytes: Long,
        timeoutMs: Long,
        hostApi: QuickJsHostApi?
    ): Long

    external fun eval(runtimePtr: Long, script: String, filename: String): String

    external fun call(runtimePtr: Long, functionName: String, requestJson: String): String

    external fun closeRuntime(runtimePtr: Long)
}
