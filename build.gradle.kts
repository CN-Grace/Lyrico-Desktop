// Top-level build file.
//
// Lyrico-Desktop is a Kotlin/JVM + Compose Multiplatform Desktop project: every module is a plain
// JVM target, so the Android Gradle Plugin is no longer applied anywhere (see PLAN.md, phase 2).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
