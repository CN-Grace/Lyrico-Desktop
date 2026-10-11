package com.lonx.lyrico.data.model.cache

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.cache_category_image
import com.lonx.lyrico.resources.cache_category_network
import com.lonx.lyrico.resources.cache_category_other
import org.jetbrains.compose.resources.StringResource

/**
 * 「清理缓存」对话框里逐项列出的缓存类目，每个类目对应 `AppDirectories` 下的一个真实目录。
 *
 * Android 的 `EXTERNAL`（`Context.externalCacheDir`）没有跟着搬过来：那是安卓「应用专属外部存储」
 * 的概念，Windows 没有对应位置，留着只会让对话框永远显示一行 `0 B`。三个类目的对应关系见
 * `com.lonx.lyrico.utils.CacheManager`。
 */
enum class CacheCategory(
    val labelRes: StringResource
) {
    IMAGE(Res.string.cache_category_image),
    NETWORK(Res.string.cache_category_network),
    OTHER(Res.string.cache_category_other)
}
