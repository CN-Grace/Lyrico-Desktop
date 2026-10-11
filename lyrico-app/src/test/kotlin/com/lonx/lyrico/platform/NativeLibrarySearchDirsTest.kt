package com.lonx.lyrico.platform

import com.lonx.audiotag.internal.NativeLibraryLoader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 原生库的探测目录清单。
 *
 * 三种布局共存，忘一个就会出现「开发时能跑、打包后 `UnsatisfiedLinkError`」这种最难查的失败：
 * 系统属性/环境变量（启动器或安装包指定）、开发布局（`build/native/<platform>`）、以及两个**打包**
 * 布局 —— 手工便携包把库放在 `<app>/native`，而 Compose Desktop 的 app image / MSI 把
 * `appResourcesRootDir` 的内容拷到 `<app>/app/resources`。最后这个在本轮（P6 #1）补上并实测通过。
 */
class NativeLibrarySearchDirsTest {

    @Test
    fun `probes the package-relative layouts next to the launcher`() {
        val probed = NativeLibraryLoader.defaultSearchDirs().map { it.toString().replace('\\', '/') }

        assertTrue(
            probed.any { it == "native" || it.endsWith("/native") },
            "expected the hand-made portable layout: $probed",
        )
        assertTrue(
            probed.any { it.contains("/app/resources/native") || it == "app/resources/native" },
            "expected the compose desktop app image layout: $probed",
        )
    }

    @Test
    fun `probes the development layout`() {
        val probed = NativeLibraryLoader.defaultSearchDirs().map { it.toString().replace('\\', '/') }

        assertTrue(
            probed.any { it.contains("/build/native/windows-x64") },
            "expected the development layout: $probed",
        )
    }
}
