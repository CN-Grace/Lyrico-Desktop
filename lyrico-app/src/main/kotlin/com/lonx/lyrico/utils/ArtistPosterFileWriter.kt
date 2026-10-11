package com.lonx.lyrico.utils

import com.lonx.lyrico.domain.poster.ArtistPosterFileNaming
import com.lonx.lyrico.utils.logging.PlatformLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把一张海报写进艺术家海报文件夹。
 *
 * Android 里文件夹是用户通过 SAF 授权的目录，写文件要走 `DocumentsContract`：
 * 先建文档、拿到文档 Uri、写流、再删旧文档、最后改名。桌面文件夹就是普通目录，整条链压成
 * `File(folder, name)`（和 [SiblingFileWriter] 记录的是同一次收缩），但下面两件事必须原样保留：
 *
 * * **先写完整的临时文件，再删旧的、再改名。** 直接把 JPEG/PNG 字节写进旧的 `.webp`/`.mp4`
 *   文件会先把它截断，写到一半失败就把原海报毁了；临时文件全程是一份完整的图。
 * * **删不掉旧文件时不改名。** 此时两个文件都留着（多一张图总比丢图好），返回 `null` 让界面
 *   提示没存成。
 *
 * 文件名由 [ArtistPosterFileNaming] 按艺术家名生成：外置海报是**按文件名认人**的，所以用户
 * 选图时叫什么名字无所谓，落盘的名字一定是这位艺术家。
 */
object ArtistPosterFileWriter {

    private const val TAG = "ArtistPosterWriter"

    /**
     * 写入 [data]；返回真正写下去的文件名，`null` 表示没有写入。
     *
     * 读写失败（目录被删、磁盘报错）与「没有可用文件夹」在这里不作区分：对用户来说都是
     * 「没存成」，界面统一提示稍后重试即可。
     */
    suspend fun write(
        artistName: String,
        data: ByteArray,
        mimeType: String,
        posterFolder: String?
    ): String? = withContext(Dispatchers.IO) {
        if (data.isEmpty() || posterFolder == null) return@withContext null

        val stem = ArtistPosterFileNaming.stemFor(artistName) ?: return@withContext null
        val extension = ArtistPosterFileNaming.extensionFor(mimeType)

        runCatching {
            writeToFolder(
                folder = posterFolder,
                stem = stem,
                extension = extension,
                artistName = artistName,
                data = data
            )
        }.getOrElse { error ->
            PlatformLog.w(TAG, "写入海报文件夹失败: $posterFolder", error)
            null
        }
    }

    private fun writeToFolder(
        folder: String,
        stem: String,
        extension: String,
        artistName: String,
        data: ByteArray
    ): String? {
        val directory = File(folder)
        // `null` 表示目录被删除或不是目录（授权失效在桌面不存在），不能与空目录混为一谈。
        val files = directory.listFiles()?.filterNot { it.isDirectory } ?: return null
        val existing = ArtistPosterFileNaming
            .matchesArtist(files.map { it.name }, artistName)
            .firstOrNull()

        val existingFile = existing?.let { name -> files.firstOrNull { it.name == name } }
        val desiredFile = File(directory, "$stem.$extension")
        // 没有旧文件时直接写目标名；有旧文件时先写临时名，等旧文件删掉再改名
        val stagedFile = if (existingFile == null) {
            desiredFile
        } else {
            File(directory, "${stem}_lyrico_${System.currentTimeMillis()}.$extension")
        }

        // `outputStream()` 截断写，等价于 Android 的 "wt" 模式：重存一次是替换而不是追加
        val written = runCatching {
            stagedFile.outputStream().use { outputStream ->
                outputStream.write(data)
                outputStream.flush()
            }
            true
        }.getOrDefault(false)

        if (!written) {
            stagedFile.delete()
            return null
        }

        if (existingFile == null) return desiredFile.name

        // 新文件已经完整落盘，现在才动旧文件。删不掉时保留两者，至少不会丢图。
        if (!existingFile.delete()) {
            stagedFile.delete()
            return null
        }

        return if (stagedFile.renameTo(desiredFile)) desiredFile.name else stagedFile.name
    }
}
