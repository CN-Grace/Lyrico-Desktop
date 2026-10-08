// Aliyun mirrors come first on purpose: this machine reaches repo.maven.apache.org through a
// transparent proxy that throttles to ~0.7 MB/s and periodically drops the TLS handshake ("Remote
// host terminated the handshake") on the multi-megabyte Kotlin plugin jars. Aliyun serves the same
// artifacts at ~20 MB/s, measured. The upstream repositories stay as fallbacks for anything the
// mirror has not picked up yet.
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "Lyrico"
include(":lyrico-audiotag")

// :lyrico-app is not part of the build yet: it is still an Android module and is being re-created
// as a Compose Multiplatform Desktop module (PLAN.md phase 2). Until then it is excluded so the
// remaining modules configure without the Android Gradle Plugin.
