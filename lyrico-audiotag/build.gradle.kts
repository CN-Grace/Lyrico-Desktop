// lyrico-audiotag — the native TagLib/ebur128/quickjs-ng bridge.
//
// Desktop port: this used to be an Android library with an externalNativeBuild (CMake) target. The
// JVM side is now a plain Kotlin/JVM library, and the native DLLs are produced out-of-band by
// scripts/build-native.ps1 (MSVC + CMake/Ninja) into build/native/windows-x64/. Gradle never
// compiles src/main/cpp — it only tells the tests where the built DLLs and the TagLib fixtures are.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // AudioTagReader/AudioTagWriter are suspend functions, so coroutines are part of the API.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}

val nativeDir = rootProject.layout.projectDirectory.dir("build/native/windows-x64")
val tagLibFixtures = layout.projectDirectory.dir("src/main/cpp/taglib/tests/data")

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    // Consumed by NativeLibraryLoader.defaultSearchDirs() and by the binding tests.
    systemProperty("lyrico.native.dir", nativeDir.asFile.absolutePath)
    systemProperty("lyrico.tests.fixtures", tagLibFixtures.asFile.absolutePath)
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
