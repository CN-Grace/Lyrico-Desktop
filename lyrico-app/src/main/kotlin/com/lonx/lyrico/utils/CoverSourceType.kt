package com.lonx.lyrico.utils

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 封面图片来源的分类：决定「把这张图写进标签 / 写到磁盘」时用哪条读取路径。
 *
 * 桌面端只有四种真实来源，比 Android 少三种，少掉的都是"内容解析器"这条链上的：
 *
 * - `CONTENT_OR_FILE_URI` 与 `URI`：Android 拿到的是 `content://` / `file://` 文档 `Uri`，必须经
 *   `Context.contentResolver.openInputStream` 才能读。Windows 上用户挑的是一个真实路径，也没有
 *   任何地方会再产出 `file://` 字符串（`songs.uri` 存的是绝对路径），所以这两个分支在桌面端
 *   没有对应的输入，删掉比留着一个永远不会命中的分支更诚实。
 * - Android 的 `Bitmap` 换成 Compose 的 [ImageBitmap]：裁剪/取图的界面层在桌面端全程用
 *   `ImageBitmap`，写盘时再交给 `ImageIO` 编码（见 `EditMetadataViewModel`）。
 */
enum class CoverSourceType {
    BYTE_ARRAY,
    IMAGE_BITMAP,
    NETWORK_URL,
    FILE_PATH,
    UNSUPPORTED
}

/**
 * 判断 [source] 是哪一类来源。
 *
 * 字符串一律按"网络地址还是本地路径"二分：桌面端字符串来源只有这两种（标签里的字节数组、
 * 界面层传下来的 [ImageBitmap] 是另外两个分支）。
 */
fun getCoverSourceType(source: Any?): CoverSourceType = when (source) {
    is ByteArray -> CoverSourceType.BYTE_ARRAY
    is ImageBitmap -> CoverSourceType.IMAGE_BITMAP
    is String -> source.trim().toCoverSourceType()
    else -> CoverSourceType.UNSUPPORTED
}

private fun String.toCoverSourceType(): CoverSourceType = when {
    startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true) -> CoverSourceType.NETWORK_URL

    else -> CoverSourceType.FILE_PATH
}
