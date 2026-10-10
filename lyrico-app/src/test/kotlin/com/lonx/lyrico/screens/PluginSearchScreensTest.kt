package com.lonx.lyrico.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.lonx.lyrico.data.support.LocalGitHubServer
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.plugin.source.SourcePluginInstaller
import com.lonx.lyrico.plugin.support.pluginManifestJson
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.cd_no_results
import com.lonx.lyrico.ui.navigation.DevStartDestination
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.navigation.SearchCoverDestination
import com.lonx.lyrico.ui.navigation.SearchLyricsDestination
import com.lonx.lyrico.ui.navigation.SearchResultsDestination
import com.lonx.lyrico.ui.theme.LyricoTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The three plugin-driven search screens, each driven end to end.
 *
 * These screens are the port's heaviest integration point: a route argument has to survive nav
 * encoding, reach a view model, be handed to a **JavaScript plugin running inside QuickJS**, and come
 * back as a list Compose renders. Nothing in that chain is mocked here -- the plugin is a real zip
 * installed through the shipped installer into the app graph's install root, the JavaScript runs in the
 * real native engine, and the HTTP-dependent parts talk to a loopback server.
 *
 * Each screen is entered through the shipped [LyricoNavHost] using the dev start-route affordance
 * (`-Dlyrico.start.route`'s in-code equivalent, [DevStartDestination]) because these screens have no
 * in-app entry point yet: their only Android callers live in `EditMetadataScreen`, which is still in the
 * java tree and belongs to a later batch. The route string itself is built by the destination class, so
 * the nav encoding is exercised too.
 *
 * The scripts are written against the documented plugin contract: `searchSongs(request)`,
 * `getLyrics(request)` and `searchCovers(request)` are handed a **live JavaScript object** (not a JSON
 * string -- a script that calls `JSON.parse` on it gets `SyntaxError: unexpected token: 'object'`, which
 * is exactly how this test suite first failed) and answer with JSON. `apiVersion` is left at the
 * fixture's 5, which is the *strict* end of the contract -- covers must carry title/artist/album/date/
 * artwork and lyrics candidates must carry `tags.ti/ar/al/date` -- so a passing test also pins that this
 * port still honours the api-4 envelope rather than the lenient one.
 */
class PluginSearchScreensTest {

    private val workingDir: File = screenTempDir("lyrico-search-screens")
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()

    private lateinit var installer: SourcePluginInstaller

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        installer = GlobalContext.get().get()
    }

    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
        workingDir.deleteRecursively()
    }

    private fun text(res: StringResource): String = runBlocking { getString(res) }

    private fun install(
        id: String,
        name: String,
        capabilities: List<String>,
        script: String,
    ) = PluginScreenFixtures.install(
        installer = installer,
        installRoot = directories.pluginInstallRoot,
        manifestJson = pluginManifestJson(id = id, name = name, capabilities = capabilities),
        script = script,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the keyword from the route reaches the plugin's javascript and its songs are listed`() = runComposeUiTest {
        install(
            id = "net.example.metadata",
            name = "Metadata Source",
            capabilities = listOf("searchSongs"),
            script = """
                globalThis.searchSongs = function (request) {
                  return [
                    { id: "m-1", title: "hit:" + request.keyword, artist: "周华健", album: "朋友",
                      duration: 265000, year: "1997" },
                    { id: "m-2", title: "hit2:" + request.keyword, artist: "李宗盛", album: "山丘",
                      duration: 300000, year: "2013" }
                  ];
                };
            """.trimIndent(),
        )

        // The keyword carries a space, an ampersand and a percent sign: all three have to survive the
        // route encoding, the view model and the JSON request before the script can echo them back.
        val keyword = "朋友 & 朋友 100%"
        setContent {
            LyricoTheme {
                LyricoNavHost(
                    startDestination = DevStartDestination(SearchResultsDestination(keyword).route),
                )
            }
        }

        waitUntil("the plugin's echoed title must be rendered", 15_000) {
            onAllNodesWithText("hit:$keyword", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("hit2:$keyword", substring = true).assertExists()
        onNodeWithText("周华健", substring = true).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a plugin that finds nothing renders the empty result state`() = runComposeUiTest {
        install(
            id = "net.example.empty",
            name = "Empty Source",
            capabilities = listOf("searchSongs"),
            script = "globalThis.searchSongs = () => ([]);",
        )

        setContent {
            LyricoTheme {
                LyricoNavHost(
                    startDestination = DevStartDestination(SearchResultsDestination("nothing").route),
                )
            }
        }

        waitUntil("the no-results message", 15_000) {
            onAllNodesWithText(text(Res.string.cd_no_results)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the lyrics screen lists the plugin's candidates and shows the lyrics it returns`() = runComposeUiTest {
        install(
            id = "net.example.lyrics",
            name = "Lyrics Source",
            capabilities = listOf("searchSongs", "getLyrics"),
            script = """
                globalThis.searchSongs = function (request) {
                  return [
                    { id: "l-1", title: "歌词:" + request.keyword, artist: "周华健", album: "朋友",
                      duration: 265000, year: "1997" }
                  ];
                };
                globalThis.getLyrics = function (request) {
                  return [
                    {
                      tags: { ti: "朋友", ar: "周华健", al: "朋友", date: "1997" },
                      type: "lrc",
                      lrc: "[00:01.00]朋友一生一起走\n[00:05.00]那些日子不再有"
                    }
                  ];
                };
            """.trimIndent(),
        )

        // `SearchLyricsScreen` initialises its keyword from title + artist, so this asserts the route's
        // four arguments were carried into the request that the script saw.
        setContent {
            LyricoTheme {
                LyricoNavHost(
                    startDestination = DevStartDestination(
                        SearchLyricsDestination(
                            title = "朋友",
                            artist = "周华健",
                            album = "朋友",
                            date = "1997",
                        ).route,
                    ),
                )
            }
        }

        waitUntil("the candidate built from the script's searchSongs reply", 15_000) {
            onAllNodesWithText("歌词:朋友 周华健", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Opening the candidate is what calls `getLyrics`; the preview only has text if that second
        // script function ran and its LRC payload was parsed and formatted by the shipped pipeline.
        onNodeWithText("歌词:朋友 周华健", substring = true).performClick()
        waitUntil("the lyrics fetched from the plugin", 15_000) {
            onAllNodesWithText("朋友一生一起走", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the cover screen renders the artwork served over http and its probed size`() = runComposeUiTest {
        val server = LocalGitHubServer().start()
        try {
            // A real PNG, encoded by the JDK and served over loopback HTTP: the size badge can only say
            // 37x19 if the port's own dimension probe fetched and decoded the response.
            val png = ByteArrayOutputStream().also { out ->
                ImageIO.write(BufferedImage(37, 19, BufferedImage.TYPE_INT_RGB), "png", out)
            }.toByteArray()
            server.respondBytes("/cover.png", png, contentType = "image/png")
            val coverUrl = "http://127.0.0.1:${server.port}/cover.png"

            install(
                id = "net.example.cover",
                name = "Cover Source",
                capabilities = listOf("searchCovers"),
                script = """
                    globalThis.searchCovers = function (request) {
                      return [
                        { id: "c-1", title: "封面:" + request.keyword, artist: "周华健", album: "朋友",
                          year: "1997", picUrl: "$coverUrl" }
                      ];
                    };
                """.trimIndent(),
            )

            setContent {
                LyricoTheme {
                    LyricoNavHost(
                        startDestination = DevStartDestination(
                            SearchCoverDestination("朋友").route,
                        ),
                    )
                }
            }

            waitUntil("the cover the script returned", 15_000) {
                onAllNodesWithText("封面:朋友", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            waitUntil("the dimensions probed from the served image", 15_000) {
                onAllNodesWithText("37×19").fetchSemanticsNodes().isNotEmpty()
            }
            check(server.requestedPaths.contains("/cover.png")) {
                "the cover screen must have fetched the artwork, requests were ${server.requestedPaths}"
            }
        } finally {
            server.stop()
        }
    }
}
