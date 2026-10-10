package com.lonx.lyrico.probe

import com.lonx.lyrico.data.model.entity.isEnabledFor
import com.lonx.lyrico.data.model.plugin.PluginSourceType
import com.lonx.lyrico.data.repository.SourcePluginRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.plugin.source.SourcePluginInstaller
import com.lonx.lyrico.plugin.support.ManifestConfigField
import com.lonx.lyrico.plugin.support.pluginArchive
import com.lonx.lyrico.plugin.support.pluginManifestJson
import com.lonx.lyrico.ui.navigation.SearchCoverDestination
import com.lonx.lyrico.ui.navigation.SearchLyricsDestination
import com.lonx.lyrico.ui.navigation.SearchResultsDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Installs a development plugin into the app's own data folder, so a capture of the running window can
 * show the plugin screens with content.
 *
 * This is a tool, not an assertion: it is the setup half of the C5b real-window evidence in PLAN.md,
 * the counterpart of `DevLibrarySeederTest`. It is skipped unless asked for, because it writes into
 * the app's database and plugin folder:
 *
 * ```
 * ./gradlew :lyrico-app:test --tests "*DevPluginSeederTest*" -Plyrico.seedDevPlugin=1
 * ./gradlew :lyrico-app:run -Dlyrico.start.route=plugin_manager
 * powershell -File scripts/capture-window.ps1 -TitleLike "Lyrico 1.6" -OutputPath out.png
 * ```
 *
 * Everything goes through the shipped installer and the real DI graph, so what the window shows is what
 * a user who imported this archive would see: a real zip, a real `manifest.json`, a real Room row and a
 * real QuickJS script. The script answers with a fixed list on purpose -- the point of the capture is
 * the *port's* screens, and a plugin that needs a network API would make the evidence depend on
 * somebody else's server. It also prints the encoded routes for the three search screens, because the
 * dev start-route override takes a route string and hand-encoding CJK arguments is error-prone.
 */
class DevPluginSeederTest {

    @Test
    fun `install a development plugin for a window capture`() {
        assumeTrue(
            "pass -Plyrico.seedDevPlugin=1 to install the demo plugin into the app's data folder",
            System.getProperty("lyrico.seedDevPlugin") == "1",
        )

        runBlocking {
            val directories = AppDirectories.resolve()
            println("SEED-PLUGIN dataDir=${directories.root}")
            println("SEED-PLUGIN installRoot=${directories.pluginInstallRoot}")

            val covers = writeCovers(File(directories.root, "demo-covers"))

            startKoin { modules(desktopAppModule(directories)) }
            try {
                val koin = GlobalContext.get()
                val installer = koin.get<SourcePluginInstaller>()
                val repository = koin.get<SourcePluginRepository>()

                // A fresh install, so a second capture run shows one plugin instead of an upgrade of
                // whatever the previous run left behind.
                if (repository.getPlugin(PLUGIN_ID) != null) {
                    repository.uninstallPlugin(PLUGIN_ID)
                    println("SEED-PLUGIN uninstalled the previous install")
                }

                val session = installer.prepareImport(
                    pluginArchive(manifestJson = manifestJson(), script = script(covers)),
                    directories.pluginInstallRoot,
                )
                check(session.failed.isEmpty()) { "the demo archive failed to import: ${session.failed}" }
                val installed = installer.installPrepared(session, enabled = true)
                check(installed.failed.isEmpty()) { "the demo archive failed to install: ${installed.failed}" }

                val row = repository.getPlugin(PLUGIN_ID)
                println("SEED-PLUGIN installed=$row")
                row?.let { plugin ->
                    println(
                        "SEED-PLUGIN enabled metadata=${plugin.isEnabledFor(PluginSourceType.METADATA)} " +
                            "lyrics=${plugin.isEnabledFor(PluginSourceType.LYRICS)} " +
                            "cover=${plugin.isEnabledFor(PluginSourceType.COVER)}",
                    )
                }
                println("SEED-PLUGIN plugins=${repository.observePlugins().first().map { it.id }}")
                println("SEED-PLUGIN files=${File(directories.pluginInstallRoot, PLUGIN_ID).listFiles()?.map { it.name }}")
            } finally {
                stopKoin()
            }

            // The routes the three search screens are captured with. `DevStartDestination` takes the
            // route string verbatim, so these are exactly what the app parses at start-up.
            println("SEED-PLUGIN route.results=${SearchResultsDestination("朋友 & 朋友").route}")
            println("SEED-PLUGIN route.lyrics=${SearchLyricsDestination("山丘", "李宗盛", "山丘", "2013").route}")
            println("SEED-PLUGIN route.cover=${SearchCoverDestination("朋友").route}")
        }
    }

    /** Real PNGs next to the plugin, so the cover grid's size badge has something to probe. */
    private fun writeCovers(directory: File): List<String> {
        directory.mkdirs()
        return listOf("cover-1.png" to (600 to 600), "cover-2.png" to (500 to 500))
            .map { (name, size) ->
                val file = File(directory, name)
                val image = BufferedImage(size.first, size.second, BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                graphics.color = Color(0x2E, 0x7D, 0x32)
                graphics.fillRect(0, 0, size.first, size.second)
                graphics.dispose()
                ImageIO.write(image, "png", file)
                println("SEED-PLUGIN cover=${file.absolutePath} size=${size.first}x${size.second}")
                file.toURI().toString()
            }
    }

    private fun manifestJson(): String = pluginManifestJson(
        id = PLUGIN_ID,
        name = "演示音源 Demo Source",
        versionName = "1.6.0",
        capabilities = listOf("searchSongs", "getLyrics", "searchCovers"),
        configFields = listOf(
            ManifestConfigField(
                key = "baseUrl",
                title = "API 地址",
                type = "text",
                required = true,
                defaultValue = "https://example.com/api",
            ),
            ManifestConfigField(key = "apiKey", title = "API Key", type = "text", required = false),
            ManifestConfigField(
                key = "guide",
                title = "接入说明",
                type = "markdown",
                required = false,
                defaultValue = "## 接入说明\n\n在插件配置里填入自己的 **API Key** 后即可使用。\n\n" +
                    "- 支持搜索歌曲、歌词与封面\n- 结果会带上来源标签",
            ),
        ),
    )

    /**
     * The script answers from a fixed list. It echoes the request keyword back into the titles so a
     * capture shows that the route argument reached JavaScript, and it reports every capability the
     * manifest declares so all three search screens have something to render.
     */
    private fun script(covers: List<String>): String = """
        var songs = function (request) {
          return [
            { id: "demo-1", title: request.keyword + " · 朋友", artist: "周华健", album: "朋友",
              duration: 265000, year: "1997", picUrl: "${covers.first()}" },
            { id: "demo-2", title: request.keyword + " · 花心", artist: "周华健", album: "花心",
              duration: 248000, year: "1993", picUrl: "${covers.last()}" },
            { id: "demo-3", title: request.keyword + " · 山丘", artist: "李宗盛", album: "山丘",
              duration: 319000, year: "2013", picUrl: "${covers.first()}" }
          ];
        };

        globalThis.searchSongs = songs;
        globalThis.searchCovers = songs;

        globalThis.getLyrics = function (request) {
          return [
            {
              tags: { ti: "朋友", ar: "周华健", al: "朋友", date: "1997" },
              type: "lrc",
              lrc: "[00:01.00]朋友一生一起走\n[00:05.00]那些日子不再有\n[00:09.00]一句话 一辈子"
            }
          ];
        };
    """.trimIndent()

    private companion object {
        const val PLUGIN_ID = "net.example.demo"
    }
}
