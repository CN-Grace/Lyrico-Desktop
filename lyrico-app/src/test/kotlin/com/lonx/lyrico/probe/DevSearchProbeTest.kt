package com.lonx.lyrico.probe

import com.lonx.lyrico.data.openLyricoDatabase
import com.lonx.lyrico.data.song.search.SongSearchRepositoryImpl
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * 开发库能不能被本地搜索查到 —— 一个诊断工具，不是断言。
 *
 * 真窗口里往搜索框打字后结果为空（`docs/port-evidence/c37d-local-search-results.png`），而临时库上的
 * 无头测试是通过的，所以要分清「代码问题」与「这份数据的问题」：这里把开发库**复制**一份出来，用同一段
 * 仓库代码查一次。
 *
 * 只在给了 `-Plyrico.probeDevSearch=<关键字>` 时跑（它要读 `lyrico-app/data`）。
 */
class DevSearchProbeTest {

    @Test
    fun `search the development database for a keyword`() {
        val keyword = System.getProperty("lyrico.probeDevSearch")
        assumeTrue("needs -Plyrico.probeDevSearch=<keyword>", !keyword.isNullOrBlank())

        runBlocking {
            val source = File("data/lyrico.db")
            assumeTrue("no development database at ${source.absolutePath}", source.isFile)
            val dir = Files.createTempDirectory("lyrico-dev-search").toFile()
            val copy = File(dir, "lyrico.db")
            Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            for (suffix in listOf("-wal", "-shm")) {
                val side = File(source.parentFile, source.name + suffix)
                if (side.isFile) Files.copy(side.toPath(), File(dir, copy.name + suffix).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            val db = openLyricoDatabase(dir)
            try {
                val repo = SongSearchRepositoryImpl(db)
                val hits = repo.searchSongsForLocalSearch(keyword!!).first()
                println("PROBE devSearch keyword='$keyword' hits=${hits.size} titles=${hits.take(5).map { it.title }}")
            } finally {
                db.close()
                dir.deleteRecursively()
            }
        }
    }
}
