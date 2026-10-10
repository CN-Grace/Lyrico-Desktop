package com.lonx.lyrico.worker.processor

import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.BatchMatchConfig
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.metadata.MetadataWriteMode
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRepository
import com.lonx.lyrico.data.song.scan.LibraryScanRequest
import com.lonx.lyrico.data.song.tag.AudioTagFieldKey
import com.lonx.lyrico.data.song.tag.AudioTagMutation
import com.lonx.lyrico.data.song.tag.AudioTagMutationMode
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.data.song.tag.AudioTagWriteResult
import com.lonx.lyrico.data.song.tag.FieldMutation
import com.lonx.lyrico.data.support.LocalGitHubServer
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.plugin.source.PluginSearchSourceManager
import com.lonx.lyrico.plugin.source.SourcePluginInstaller
import com.lonx.lyrico.plugin.support.ManifestConfigField
import com.lonx.lyrico.plugin.support.pluginArchive
import com.lonx.lyrico.plugin.support.pluginManifestJson
import com.lonx.lyrico.viewmodel.SortBy
import com.lonx.lyrico.viewmodel.SortOrder
import com.lonx.lyrico.worker.BatchTaskRunner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C6b 的验收测试：**三个「匹配」处理器走真实插件链路，改真文件、写真数据库行。**
 *
 * 链路上没有任何一个环节是假的：插件是字节级真实 zip（`ZipOutputStream` → 安装器解包 → Room 行），
 * 脚本由 QuickJS 真实执行，`Platform.http.getText` 打到本机回环 HTTP 服务，HTTP 响应经
 * `PluginJsonParser` 解析成 `SongSearchResult`，标签由 TagLib 真实读回，任务由真实
 * [BatchTaskRunner] 驱动。插件配置（`baseUrl`）也走处理器真正使用的那条 `sourceSettings` →
 * `applyConfig` 通道，而不是测试从后门塞进去。
 *
 * 为什么值得单独写这一层：MATCH_* 是整条链路唯一「由外部数据决定写什么」的部分。它的失败方式
 * 单元测试看不见——得分门槛把好结果扔掉（任务成功但一个字都没写）、补丁模式把没变的字段一起写下去
 * （冲掉用户的标签）、封面的 URL 没有被真正下载就开始写标签。所以这里每一条断言都验到磁盘上的字节。
 *
 * 有意不覆盖：ReplayGain（需要 ffmpeg sidecar，见 C6d）与导出处理器（C6e）。
 */
class MatchProcessorEndToEndTest {

    private val dataRoot: File = Files.createTempDirectory("lyrico-match-processors").toFile()
    private val directories = AppDirectories(root = dataRoot, isPortable = true).prepare()
    private val musicDir: Path = Files.createTempDirectory("lyrico-match-library")
    private val server = LocalGitHubServer().start()

    private val json = Json { encodeDefaults = true }

    private lateinit var database: LyricoDatabase
    private lateinit var tasks: BatchTaskRepository
    private lateinit var runner: BatchTaskRunner
    private lateinit var songs: SongLibraryRepository
    private lateinit var tags: AudioTagRepository
    private lateinit var scanner: LibraryScanRepository
    private lateinit var appLog: AppLogRepository
    private lateinit var installer: SourcePluginInstaller

    /** The library root row is inserted once per test, on the first scan. */
    private var rootAdded = false

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        startKoin { modules(desktopAppModule(directories)) }
        val koin = GlobalContext.get()
        database = koin.get()
        tasks = koin.get()
        runner = koin.get()
        songs = koin.get()
        tags = koin.get()
        scanner = koin.get()
        appLog = koin.get()
        installer = koin.get()
        // TagLib's fixtures are all shorter than a minute and the app's own default drops short
        // audio, so a scan would (correctly) insert nothing.
        runBlocking { koin.get<SettingsRepository>().saveIgnoreShortAudio(false) }
    }

    @AfterTest
    fun tearDown() {
        // QuickJS runtimes hold native memory; the manager is a singleton, so close it while Koin
        // still knows about it.
        runCatching { GlobalContext.get().get<PluginSearchSourceManager>().close() }
        runCatching { stopKoin() }
        server.stop()
        dataRoot.deleteRecursively()
        musicDir.toFile().deleteRecursively()
    }

    @Test
    fun `a metadata match writes the plugin's fields to the file and the row`() = runBlocking<Unit> {
        server.respond(
            "/search",
            """
            {"items":[
              {"id":101,"name":"山丘","artists":["李宗盛"],"album":{"name":"山丘专辑"},
               "durationMs":319000,"comment":"来自插件"}
            ]}
            """.trimIndent(),
        )
        installPlugin()
        val song = placeSong(title = "山丘", artist = "李宗盛")
        assertNull(song.album, "precondition: the file starts with no album")

        val taskId = createTask(
            type = BatchTaskType.MATCH_METADATA,
            song = song,
            config = matchConfig(
                MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE,
                MetadataFieldTarget.COMMENT to MetadataWriteMode.SUPPLEMENT,
            ),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)

        // 1. The request really went out to the plugin, which really went out to the API.
        val request = server.requests.first { it.path.startsWith("/search") }
        assertEquals("GET", request.method)
        assertContains(request.path, "q=")

        // 2. On disk: the blank album was filled, the comment came along, and the title the file
        //    already had was not touched (overwrite only where the user asked for it).
        val written = tags.read(song.uri, AudioTagReadOptions())
        assertEquals("山丘专辑", written.album)
        assertEquals("来自插件", written.comment)
        assertEquals("山丘", written.title, "a field that was not a target must survive untouched")
        assertEquals("李宗盛", written.artist)

        // 3. In the row: `SaveAudioTagsUseCase` re-read the file and stored that snapshot.
        val row = songs.getSongByUri(song.uri)
        assertNotNull(row)
        assertEquals("山丘专辑", row.album)
        assertEquals("来自插件", row.comment)
        assertEquals(1, songs.getSongCount(), "a match must update the row it matched, not insert one")

        val item = tasks.observeItems(taskId).first().single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status)
        assertNull(item.errorMessage)

        // 4. The plugin reported the call to the app log, keyed to the plugin - which is how the
        //    (not yet ported) task detail screen explains which source answered.
        val pluginLog = appLog.observeByRelatedId(PLUGIN_ID).first()
            .single { it.message == "Plugin song search returned 1 result(s)" }
        assertEquals(AppLogType.PLUGIN, pluginLog.type)
        assertContains(pluginLog.detail.orEmpty(), "keyword=山丘")
    }

    @Test
    fun `a metadata match with every field disabled is skipped instead of searched`() = runBlocking<Unit> {
        installPlugin()
        val song = placeSong(title = "山丘", artist = "李宗盛")

        val taskId = createTask(
            type = BatchTaskType.MATCH_METADATA,
            song = song,
            config = matchConfig(
                // The user turned every switch off: there is nothing to look for, so the plugin must
                // not even be asked (a search per file would be a lot of traffic for nothing).
                MetadataFieldTarget.ALBUM to MetadataWriteMode.DISABLED,
                MetadataFieldTarget.TITLE to MetadataWriteMode.DISABLED,
            ),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status, "a skip is not a failure")
        val item = tasks.observeItems(taskId).first().single()
        assertEquals(BatchTaskStatus.SKIPPED, item.status)
        assertEquals("No fields need processing", item.resultJson, "the reason is what the UI shows")
        assertEquals(emptyList(), server.requests, "the plugin was never asked")

        val written = tags.read(song.uri, AudioTagReadOptions())
        assertEquals("山丘", written.title)
        assertNull(written.album)
    }

    @Test
    fun `a metadata match that the plugin cannot answer is skipped with the score reason`() = runBlocking<Unit> {
        // The API knows the song but returns nothing matching: the engine found no candidate at all.
        server.respond("/search", """{"items":[]}""")
        installPlugin()
        val song = placeSong(title = "没有这首歌", artist = "无名")

        val taskId = createTask(
            type = BatchTaskType.MATCH_METADATA,
            song = song,
            config = matchConfig(MetadataFieldTarget.ALBUM to MetadataWriteMode.OVERWRITE),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)
        val item = tasks.observeItems(taskId).first().single()
        assertEquals(BatchTaskStatus.SKIPPED, item.status)
        assertEquals("No match found", item.resultJson)
        assertTrue(server.requests.isNotEmpty(), "the source was asked before giving up")
        assertNull(tags.read(song.uri, AudioTagReadOptions()).album)
    }

    @Test
    fun `a lyrics match writes the plugin's lyrics into the file and the row`() = runBlocking<Unit> {
        server.respond(
            "/search",
            """{"items":[{"id":101,"name":"山丘","artists":["李宗盛"],"album":{"name":"山丘专辑"},"durationMs":319000}]}""",
        )
        server.respond(
            "/lyrics",
            """
            {"title":"山丘","artist":"李宗盛","album":"山丘专辑",
             "lrc":"[00:01.00]越过山丘\n[00:05.00]虽然已白了头"}
            """.trimIndent(),
        )
        installPlugin()
        val song = placeSong(title = "山丘", artist = "李宗盛")

        val taskId = createTask(
            type = BatchTaskType.MATCH_LYRICS,
            song = song,
            config = matchConfig(MetadataFieldTarget.LYRICS to MetadataWriteMode.OVERWRITE),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)
        val searched = server.requests.map { it.path.substringBefore("?") }
        assertEquals(
            "/lyrics",
            searched.last(),
            "the source searched first, then asked for the lyrics of the result it picked",
        )
        assertEquals(
            listOf("/search"),
            searched.dropLast(1).distinct(),
            "every query the engine builds is a real search; the last one wins: $searched",
        )
        assertTrue(searched.size > 1, "the engine tried more than one query before giving up")

        // On disk: the lyrics of the matched result, encoded by the app's own renderer. The exact
        // text depends on the user's lyric preferences, so assert the content is there.
        val writtenLyrics = tags.read(song.uri, AudioTagReadOptions()).lyrics
        assertNotNull(writtenLyrics, "the lyrics have to land in the tag, not only in the database")
        assertContains(writtenLyrics, "越过山丘")
        assertContains(writtenLyrics, "虽然已白了头")

        val row = songs.getSongByUri(song.uri)
        assertNotNull(row)
        assertEquals(writtenLyrics, row.lyrics, "the row stores what the file now holds")

        val item = tasks.observeItems(taskId).first().single()
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status)
    }

    @Test
    fun `a lyrics match in supplement mode leaves the lyrics the file already has`() = runBlocking<Unit> {
        server.respond("/search", """{"items":[{"id":101,"name":"山丘","artists":["李宗盛"]}]}""")
        server.respond(
            "/lyrics",
            """{"title":"山丘","artist":"李宗盛","album":"","lrc":"[00:01.00]插件歌词"}""",
        )
        installPlugin()
        val song = placeSong(
            title = "山丘",
            artist = "李宗盛",
            lyrics = "[00:01.000]我自己写的歌词",
        )

        val taskId = createTask(
            type = BatchTaskType.MATCH_LYRICS,
            song = song,
            config = matchConfig(MetadataFieldTarget.LYRICS to MetadataWriteMode.SUPPLEMENT),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)
        val item = tasks.observeItems(taskId).first().single()
        assertEquals(BatchTaskStatus.SKIPPED, item.status)
        assertEquals("Lyrics already exist", item.resultJson)
        assertEquals(emptyList(), server.requests, "supplement skips before spending a search")

        val written = tags.read(song.uri, AudioTagReadOptions()).lyrics
        assertNotNull(written)
        assertContains(written, "我自己写的歌词")
        assertTrue(!written.contains("插件歌词"), "the plugin's lyrics must not overwrite the user's")
    }

    @Test
    fun `a cover match downloads the plugin's image into the file`() = runBlocking<Unit> {
        // The v4 contract for cover results is stricter than the metadata one: the parser drops an
        // item that has no title, artist, album, **date** or cover URL, because those are what a
        // cover can be judged against. An incomplete item is not an error - it just never matches.
        val coverBytes = onePixelPng()
        server.respondBytes("/cover.png", coverBytes, contentType = "image/png")
        server.respond(
            "/covers",
            """
            {"items":[
              {"id":201,"name":"山丘","artists":["李宗盛"],"album":{"name":"山丘专辑"},
               "year":"2013","cover":"http://127.0.0.1:${server.port}/cover.png"}
            ]}
            """.trimIndent(),
        )
        installPlugin()
        val song = placeSong(title = "山丘", artist = "李宗盛")
        assertEquals(
            0,
            tags.read(song.uri, AudioTagReadOptions()).pictures.size,
            "precondition: the fixture carries no cover",
        )

        val taskId = createTask(
            type = BatchTaskType.MATCH_COVER,
            song = song,
            config = matchConfig(MetadataFieldTarget.COVER to MetadataWriteMode.OVERWRITE),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)
        val item = tasks.observeItems(taskId).first().single()
        // A skipped item also leaves the task SUCCEEDED, so the item is what has to be asserted: an
        // unusable cover result is dropped silently by the plugin parser, and this is where that shows.
        assertEquals(BatchTaskStatus.SUCCEEDED, item.status, "the cover had to be written: ${item.resultJson}")
        assertEquals(
            listOf("/covers", "/cover.png"),
            server.requests.map { it.path },
            "the cover URL the plugin returned was really fetched, not just written as a URL string",
        )

        // On disk: the bytes of the image, not a link to it. A player that has no network has to be
        // able to draw the cover.
        val picture = tags.read(song.uri, AudioTagReadOptions()).pictures.singleOrNull()
        assertNotNull(picture, "the cover has to be embedded in the tag")
        assertContains(picture.mimeType, "png", ignoreCase = true)
        assertTrue(coverBytes.contentEquals(picture.data), "the embedded image is the one the plugin served")
    }

    @Test
    fun `a match task with no installed plugin is skipped and the run still finishes`() = runBlocking<Unit> {
        // The state every fresh Windows install is in: the user has not added a source yet.
        val song = placeSong(title = "山丘", artist = "李宗盛")

        val taskId = createTask(
            type = BatchTaskType.MATCH_LYRICS,
            song = song,
            config = matchConfig(MetadataFieldTarget.LYRICS to MetadataWriteMode.OVERWRITE),
        )
        runner.run(taskId)

        assertEquals(BatchTaskStatus.SUCCEEDED, tasks.getTask(taskId)?.status)
        val item = tasks.observeItems(taskId).first().single()
        assertEquals(BatchTaskStatus.SKIPPED, item.status)
        assertEquals("No enabled lyrics source", item.resultJson)
        assertEquals(song.uri, item.songUri, "the item still names its song")
        assertNull(tags.read(song.uri, AudioTagReadOptions()).lyrics, "nothing was written")

        // The run is reported as a warning-free summary that counts the skip, so the user can see that
        // the task did nothing rather than guessing.
        val finished = appLog.observeByRelatedId(taskId).first()
            .single { it.message.contains("Batch task finished") }
        assertEquals(AppLogLevel.INFO, finished.level)
        assertEquals(AppLogType.BATCH, finished.type)
        val detail = finished.detail
        assertNotNull(detail)
        assertContains(detail, "skipped=1")
        assertContains(detail, "failure=0")
    }

    // ---------------------------------------------------------------- helpers

    /** Registers the task the way a screen does: real rows, real items, real config JSON. */
    private suspend fun createTask(
        type: BatchTaskType,
        song: SongEntity,
        config: MatchMetadataTaskConfig,
    ): String = tasks.createTask(
        type,
        listOf(song),
        json.encodeToString(MatchMetadataTaskConfig.serializer(), config),
    )

    /**
     * The config the (not yet ported) batch match screen writes. `enabledSourceOrderIds` and
     * `sourceSettings` are filled in for the one fixture plugin, so the processors really take the
     * path they take in the app: order filter, then `applyConfig` with the user's saved values.
     */
    private fun matchConfig(
        vararg modes: Pair<MetadataFieldTarget, MetadataWriteMode>,
    ) = MatchMetadataTaskConfig(
        matchConfig = BatchMatchConfig(
            targetModes = modes.toMap(),
            concurrency = 1,
        ),
        separator = "/",
        enabledSourceOrderIds = listOf(PLUGIN_ID),
        sourceSettings = mapOf(PLUGIN_ID to mapOf("baseUrl" to baseUrl)),
        concurrency = 1,
    )

    private val baseUrl: String get() = "http://127.0.0.1:${server.port}"

    /**
     * Installs the fixture plugin exactly the way the plugin screen does: build the archive, prepare
     * the import, install it enabled. Nothing here is stubbed - the script below is executed by
     * QuickJS, and `lib/01_http.js` is only reachable if `includeDirs` really concatenated it.
     */
    private suspend fun installPlugin() {
        val session = installer.prepareImport(
            pluginArchive(
                manifestJson = pluginManifestJson(
                    id = PLUGIN_ID,
                    name = "Match Fixture",
                    includeDirs = listOf("lib"),
                    capabilities = listOf("searchSongs", "getLyrics", "searchCovers"),
                    configFields = listOf(ManifestConfigField(key = "baseUrl", title = "API base URL")),
                ),
                script = PLUGIN_SCRIPT,
                extra = listOf("lib/01_http.js" to PLUGIN_HTTP_MODULE),
            ),
            directories.pluginInstallRoot,
        )
        assertEquals(emptyList(), session.failed.map { it.reason }, "the fixture archive has to prepare")
        val result = installer.installPrepared(session, enabled = true)
        assertEquals(emptyList(), result.failed.map { it.reason }, "the fixture plugin has to install")
        assertEquals(PLUGIN_ID, result.installed.single().id)
    }

    /** Copies a real audio fixture out of TagLib's vendored test files into the temp library. */
    private fun placeFile(
        fileName: String,
        title: String,
        artist: String,
        album: String? = null,
        lyrics: String? = null,
    ): Path {
        val source = Path.of(System.getProperty("lyrico.audiotag.fixtures.dir"), "bladeenc.mp3")
        assertTrue(Files.isRegularFile(source), "missing audio fixture $source")
        val folder = musicDir.resolve("Album")
        Files.createDirectories(folder)
        val target = folder.resolve(fileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

        val fields = buildMap {
            put(AudioTagFieldKey.Title, FieldMutation.Set(title))
            put(AudioTagFieldKey.Artist, FieldMutation.Set(artist))
            if (album != null) {
                put(AudioTagFieldKey.Album, FieldMutation.Set(album))
                put(AudioTagFieldKey.AlbumArtist, FieldMutation.Set(artist))
            }
            if (lyrics != null) put(AudioTagFieldKey.Lyrics, FieldMutation.Set(lyrics))
        }
        val result = runBlocking {
            tags.patch(target.toString(), AudioTagMutation(mode = AudioTagMutationMode.Patch, fields = fields))
        }
        assertIs<AudioTagWriteResult.Success>(result, "the fixture has to be taggable")
        return target
    }

    /** Places one file and returns the row the following scan produced for it. */
    private suspend fun placeSong(
        title: String,
        artist: String,
        album: String? = null,
        lyrics: String? = null,
        fileName: String = "song.mp3",
    ): SongEntity {
        val path = placeFile(fileName = fileName, title = title, artist = artist, album = album, lyrics = lyrics)
        val uri = path.toRealPath().toString()
        return scanLibrary().single { it.uri == uri }
    }

    /** The scan the app's own manager performs, awaited: real tags read into the real database. */
    private suspend fun scanLibrary(): List<SongEntity> {
        if (!rootAdded) {
            database.folderDao().insert(
                FolderEntity(path = musicDir.toRealPath().toString(), addedBySaf = true)
            )
            rootAdded = true
        }
        val result = scanner.synchronize(LibraryScanRequest(fullRescan = false, ignoreShortAudio = false))
        assertTrue(result.failures.isEmpty(), "the scan itself must not fail: ${result.failures}")
        return songs.observeSongs(SortBy.TITLE, SortOrder.ASC).first()
    }

    /** A real PNG, so TagLib detects the mime type from the bytes instead of being told. */
    private fun onePixelPng(): ByteArray = ByteArrayOutputStream().also { out ->
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", out)
    }.toByteArray()

    private companion object {
        const val PLUGIN_ID = "net.example.match"

        /**
         * The entry script. It reads the API base URL out of the config the *processor* passed in
         * (`request.config.baseUrl`), which is the user's saved value travelling the real path.
         */
        val PLUGIN_SCRIPT = """
            include("lib/01_http.js");

            globalThis.searchSongs = function (request) {
              var base = (request.config || {}).baseUrl || "";
              var body = Platform.http.getText(ExampleApi.searchUrl(base, request.keyword, request.page), {
                headers: { "X-Plugin": "match" }
              });
              var payload = JSON.parse(body);
              return (payload.items || []).map(function (item) {
                return ExampleApi.mapSong(item, request.separator);
              }).filter(function (song) { return song.id && song.title; });
            };

            globalThis.getLyrics = function (request) {
              var base = (request.config || {}).baseUrl || "";
              var song = request.song || {};
              var payload = JSON.parse(Platform.http.getText(ExampleApi.lyricsUrl(base, song), {
                headers: { "X-Plugin": "match" }
              }));
              return {
                type: "rawPlainLrc",
                tags: {
                  ti: payload.title,
                  ar: payload.artist,
                  al: payload.album,
                  date: "2013-08-01"
                },
                rawPlainLrc: payload.lrc
              };
            };

            globalThis.searchCovers = function (request) {
              var base = (request.config || {}).baseUrl || "";
              var payload = JSON.parse(Platform.http.getText(base + "/covers", {
                headers: { "X-Plugin": "match" }
              }));
              return (payload.items || []).map(function (item) {
                return ExampleApi.mapSong(item, request.separator);
              }).filter(function (song) { return song.id && song.title; });
            };
        """.trimIndent()

        /** The helper module behind `includeDirs`: URL building and response mapping. */
        val PLUGIN_HTTP_MODULE = """
            globalThis.ExampleApi = {
              searchUrl: function (base, keyword, page) {
                return base + "/search?q=" + encodeURIComponent(keyword) + "&page=" + page;
              },
              lyricsUrl: function (base, song) {
                return base + "/lyrics?title=" + encodeURIComponent(song.title || "") +
                       "&artist=" + encodeURIComponent(song.artist || "");
              },
              mapSong: function (item, separator) {
                var artists = (item.artists || []).join(separator || "/");
                var cover = item.cover || "";
                var fields = {
                  title: item.name || "",
                  artist: artists,
                  album: (item.album || {}).name || "",
                  cover_url: cover
                };
                if (item.comment) fields.comment = item.comment;
                return {
                  id: String(item.id || ""),
                  title: item.name || "",
                  artist: artists,
                  album: (item.album || {}).name || "",
                  date: String(item.year || item.releaseDate || item.date || ""),
                  duration: Number(item.durationMs || 0),
                  picUrl: cover,
                  fields: fields,
                  internal: { match_id: String(item.id || "") }
                };
              }
            };
        """.trimIndent()
    }
}
