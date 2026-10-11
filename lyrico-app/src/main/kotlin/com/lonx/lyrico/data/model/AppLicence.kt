package com.lonx.lyrico.data.model

/**
 * One third-party component the app ships with, as the licence page lists it.
 *
 * Android produced this list with the `aboutlibraries` Gradle plugin, which reads the resolved dependency
 * graph and generates a `R.raw.aboutlibraries` resource. **The desktop build has no such plugin**, so this
 * list is maintained by hand instead: the port's dependencies are declared in `gradle/libs.versions.toml`
 * and the engine/decoder ones ship as sidecars (`docs/third-party/`).
 *
 * That difference is a real limitation, recorded rather than papered over: a dependency added to
 * `libs.versions.toml` and not added here will be missing from this page. `AppLicencesTest` pins the parts
 * that can be checked mechanically (every entry names its licence and, unless it is the app itself, a URL),
 * and the licence texts themselves are the ones the upstream projects publish.
 */
data class AppLicence(
    /** Stable key for the list; the Android page used the Maven coordinate. */
    val id: String,
    val name: String,
    val version: String,
    val licences: List<String>,
    val url: String?,
)

/**
 * The components this page lists.
 *
 * Kept to the *direct* third-party components plus the two sidecars, in the order a reader is likely to
 * care about: the UI toolkit, the app's own plumbing, then media/engine pieces. Transitive libraries are not
 * enumerated -- the Android page listed them all, and doing that by hand would be a maintenance trap.
 */
object AppLicences {

    val all: List<AppLicence> = listOf(
        AppLicence(
            id = "compose-multiplatform",
            name = "Compose Multiplatform",
            version = "1.12.0",
            licences = listOf("Apache-2.0"),
            url = "https://github.com/JetBrains/compose-multiplatform",
        ),
        AppLicence(
            id = "miuix",
            name = "Miuix",
            version = "0.9.4",
            licences = listOf("Apache-2.0"),
            url = "https://github.com/miuix-kotlin/miuix",
        ),
        AppLicence(
            id = "koin",
            name = "Koin",
            version = "4.2.2",
            licences = listOf("Apache-2.0"),
            url = "https://github.com/InsertKoinIO/koin",
        ),
        AppLicence(
            id = "kotlinx-coroutines",
            name = "kotlinx.coroutines",
            version = "1.10.2",
            licences = listOf("Apache-2.0"),
            url = "https://github.com/Kotlin/kotlinx.coroutines",
        ),
        AppLicence(
            id = "kotlinx-serialization",
            name = "kotlinx.serialization",
            version = "1.11.0",
            licences = listOf("Apache-2.0"),
            url = "https://github.com/Kotlin/kotlinx.serialization",
        ),
        AppLicence(
            id = "room",
            name = "AndroidX Room",
            version = "2.8.5",
            licences = listOf("Apache-2.0"),
            url = "https://developer.android.com/jetpack/androidx/releases/room",
        ),
        AppLicence(
            id = "datastore",
            name = "AndroidX DataStore",
            version = "1.2.1",
            licences = listOf("Apache-2.0"),
            url = "https://developer.android.com/jetpack/androidx/releases/datastore",
        ),
        AppLicence(
            id = "okhttp",
            name = "OkHttp",
            version = "5.5.0",
            licences = listOf("Apache-2.0"),
            url = "https://square.github.io/okhttp/",
        ),
        AppLicence(
            id = "coil",
            name = "Coil",
            version = "3.6.2",
            licences = listOf("Apache-2.0"),
            url = "https://coil-kt.github.io/coil/",
        ),
        AppLicence(
            id = "taglib",
            name = "TagLib",
            version = "2.3.0",
            licences = listOf("LGPL-2.1", "MPL-1.1"),
            url = "https://taglib.org/",
        ),
        AppLicence(
            id = "ffmpeg",
            name = "FFmpeg (sidecar binary)",
            // Both strings come from `docs/third-party/ffmpeg-lgpl.md`, which records the pinned release
            // and the fact that this build is LGPL **v3** (`--enable-version3`), not v2.1.
            version = "N-127271-gba987fe24d",
            licences = listOf("LGPL-3.0"),
            url = "https://github.com/BtbN/FFmpeg-Builds",
        ),
        AppLicence(
            id = "quickjs-ng",
            name = "quickjs-ng (plugin runtime)",
            // No version is pinned anywhere in this repository: the engine ships as a bundled `quickjs-ng`
            // library built by `lyrico-audiotag` (`QuickJsNative` loads it), so the page says "bundled"
            // rather than inventing a release number.
            version = "bundled",
            licences = listOf("MIT"),
            url = "https://github.com/quickjs-ng/quickjs",
        ),
    )
}
