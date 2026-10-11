package com.lonx.lyrico.viewmodel

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.lonx.audiotag.model.AudioPictureType
import com.lonx.audiotag.model.AudioTagData
import com.lonx.audiotag.model.frontCoverOrFallback
import com.lonx.lyrico.data.editfield.EditFieldConfigRepository
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.search.LyricsSearchResult
import com.lonx.lyrico.data.repository.CustomTagKeyRepository
import com.lonx.lyrico.data.repository.LibraryIndexRepositoryImpl
import com.lonx.lyrico.data.repository.PlaybackRepositoryImpl
import com.lonx.lyrico.data.repository.SettingsRepositoryImpl
import com.lonx.lyrico.data.repository.createSettingsDataStore
import com.lonx.lyrico.data.song.file.AudioFileAccess
import com.lonx.lyrico.data.song.library.SongLibraryRepositoryImpl
import com.lonx.lyrico.data.song.mapper.SongMetadataMapper
import com.lonx.lyrico.data.song.mapper.SortKeyUpdater
import com.lonx.lyrico.data.song.tag.AudioTagMutationResolver
import com.lonx.lyrico.data.song.tag.AudioTagRepositoryImpl
import com.lonx.lyrico.data.song.tag.DefaultImageBytesFetcher
import com.lonx.lyrico.data.song.tag.ImageMimeTypeDetector
import com.lonx.lyrico.data.song.tag.PictureMutationResolver
import com.lonx.lyrico.data.song.tag.TagMapBuilder
import com.lonx.lyrico.data.support.RecordingAppLogRepository
import com.lonx.lyrico.data.support.TestLibrary
import com.lonx.lyrico.domain.song.usecase.OverwriteSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.ReadAudioTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsUseCase
import com.lonx.lyrico.platform.DesktopOpener
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.msg_same_album_cover_applied
import com.lonx.lyrico.resources.no_player_found
import com.lonx.lyrico.resources.replay_gain_calculate_success
import com.lonx.lyrico.resources.replay_gain_error_general
import com.lonx.lyrico.resources.unknown_error
import com.lonx.lyrico.utils.ReplayGainScanner
import com.lonx.lyrico.utils.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The single-song metadata editor, driven against real files, a real database and real TagLib writes.
 *
 * The line is the same one `SongSelectionViewModelTest` and `AlbumActionsViewModelTest` draw:
 * everything whose failure mode is real I/O is real -- the fixture is copied out of the TagLib test
 * corpus, the tags are read and written by TagLib into that file, the row and the lyric index live in
 * a file-backed `lyrico.db`, the lyrics land in real `.lrc` files, and ReplayGain runs real ffmpeg
 * over real audio. Two seams are faked, and only because a test must not reach outside the process:
 *
 * - the shell hand-off ([PlaybackRepositoryImpl] is real, its [DesktopOpener] is not, so "play" is
 *   asserted as *what would have been opened* instead of launching a player on the machine running
 *   the suite);
 * - the image *picker*: no dialog is involved, the test hands the view model the [File] the picker
 *   would have returned. The bytes that go into the tag are read from that real file.
 *
 * The interesting behaviour is not in the signatures but in what the port had to change while keeping
 * Android's semantics, so those are the things pinned here: the display source of a picked cover is a
 * *path string* and never a `File`; a cropped `ImageBitmap` is encoded to JPEG at quality 1.0 before
 * it is stored (a re-degraded crop would look it); a hidden edit field is copied back from the file
 * rather than dropped; the lyrics offset is measured from the sheet's snapshot so repeated shifts do
 * not accumulate; and every failure both lands in the state and writes an app log entry.
 *
 * Messages are asserted as `UiMessage.Localized` resources and their argument lists, never as
 * resolved text, so the machine's locale cannot make a test pass or fail for the wrong reason.
 */
class EditMetadataViewModelTest {

    private lateinit var library: TestLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var musicDir: Path
    private lateinit var settings: SettingsRepositoryImpl
    private lateinit var tags: AudioTagRepositoryImpl
    private lateinit var readTags: ReadAudioTagsUseCase
    private lateinit var overwrite: OverwriteSongTagsUseCase
    private lateinit var editFields: EditFieldConfigRepository
    private lateinit var songLibrary: SongLibraryRepositoryImpl
    private var rootFolderId: Long? = null

    private val log = RecordingAppLogRepository()

    /** What the (faked) shell was asked to open, and whether it refuses. */
    private val openedFiles = mutableListOf<File>()
    private var openFailure: IOException? = null

    @BeforeTest
    fun setUp() {
        // Every action here launches onto `viewModelScope`, which is Main, and the work behind it is
        // real Room, real file IO and real ffmpeg, so Main is a real dispatcher rather than a virtual
        // scheduler. It has to be set before the first view model is built: `visibleFields` and the
        // lyric-render config start collecting in the constructor.
        Dispatchers.setMain(Dispatchers.Default)
        library = TestLibrary()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        musicDir = Files.createTempDirectory("lyrico-edit-metadata")

        settings = SettingsRepositoryImpl(
            createSettingsDataStore(
                Files.createTempFile("lyrico-edit-metadata-settings", ".preferences_pb"),
                scope,
            )
        )
        editFields = EditFieldConfigRepository(
            createSettingsDataStore(
                Files.createTempFile("lyrico-edit-metadata-fields", ".preferences_pb"),
                scope,
            )
        )
        tags = AudioTagRepositoryImpl(
            fileAccess = AudioFileAccess(),
            mutationResolver = AudioTagMutationResolver(
                tagMapBuilder = TagMapBuilder(),
                pictureResolver = PictureMutationResolver(
                    imageBytesFetcher = DefaultImageBytesFetcher(AudioFileAccess(), OkHttpClient()),
                    mimeTypeDetector = ImageMimeTypeDetector(),
                ),
            ),
            appLogRepository = log,
        )
        readTags = ReadAudioTagsUseCase(audioTagRepository = tags, settingsRepository = settings)
        songLibrary = SongLibraryRepositoryImpl(library.database)
        val saveTags = SaveAudioTagsUseCase(
            database = library.database,
            songLibraryRepository = songLibrary,
            audioTagRepository = tags,
            customTagKeyRepository = CustomTagKeyRepository(library.database.songCustomTagKeyDao()),
            libraryIndexRepository = LibraryIndexRepositoryImpl(
                database = library.database,
                songDao = library.database.songDao(),
                indexDao = library.database.libraryIndexDao(),
                settingsRepository = settings,
            ),
            songMetadataMapper = SongMetadataMapper(SortKeyUpdater()),
        )
        // The very use case the view model writes through, so "the file on disk changed" is a
        // statement about the shipped write path and not about a stub.
        overwrite = OverwriteSongTagsUseCase(saveTags)
    }

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        Dispatchers.resetMain()
        scope.cancel()
        library.close()
        musicDir.toFile().deleteRecursively()
    }

    /**
     * The view model, with the graph the shipped module builds for it.
     *
     * Nothing here is stubbed except the shell hand-off, because a hand-built graph that quietly stops
     * matching the module's is the kind of drift a passing test cannot see.
     */
    private fun viewModel(): EditMetadataViewModel {
        val playback = PlaybackRepositoryImpl(
            desktopOpener = object : DesktopOpener {
                override fun isSupported(): Boolean = true
            override fun browse(url: String) = Unit
                override fun open(file: File) {
                    openFailure?.let { throw it }
                    openedFiles += file
                }
            },
        )
        return EditMetadataViewModel(
            songLibraryRepository = songLibrary,
            readAudioTagsUseCase = readTags,
            overwriteSongTagsUseCase = overwrite,
            settingsRepository = settings,
            playbackRepository = playback,
            replayGainScanner = ReplayGainScanner(),
            appLogRepository = log,
            editFieldConfigRepository = editFields,
        )
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun `reading a real file leaves the editing copy equal to the file's tags`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()

        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.songInfo?.uriString }, condition = { it == song.uri })

        val state = viewModel.uiState.value
        assertEquals(
            "bladeenc",
            state.fileName,
            "the title bar shows the file name without its extension, and it comes from the row",
        )
        assertNotNull(state.originalTagData)
        assertEquals(
            state.originalTagData,
            state.editingTagData,
            "the editor starts as a copy of the file, so nothing is dirty until the user types",
        )
        assertFalse(state.isEditing, "opening a song is not an edit")
    }

    @Test
    fun `a re-read refreshes the snapshot but not what the user typed`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        viewModel.updateTag { copy(title = "Typed By The User") }
        assertTrue(viewModel.uiState.value.isEditing)

        // The file changes underneath -- another editor, a batch job. The next read (`readMetadata` runs
        // on every recomposition of the screen) has to take the new tags for `originalTagData` while
        // leaving the in-flight edit alone, or the user watches their typing revert.
        overwrite(song.uri, AudioTagData(title = "Changed On Disk"))
        viewModel.readMetadata(song.uri)
        awaitUntil(
            describe = { viewModel.uiState.value.originalTagData?.title },
            condition = { it == "Changed On Disk" },
        )

        assertEquals(
            "Typed By The User",
            viewModel.uiState.value.editingTagData?.title,
            "an edit the user has already made must survive the re-read",
        )
        assertTrue(viewModel.uiState.value.isEditing, "and the editor must still be in editing mode")
    }

    // -------------------------------------------------------------------- cover

    @Test
    fun `picking a cover stores its bytes, its mime type and its path, never the File itself`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        val picked = writeTestImage("picked.png", "png")
        viewModel.updateCover(picked.toFile())
        awaitUntil(
            describe = { viewModel.uiState.value.coverUri },
            condition = { it == picked.toFile().absolutePath },
        )

        val state = viewModel.uiState.value
        assertEquals("image/png", state.picture?.mimeType, "the mime type comes from the extension, as `getType` would have")
        assertContentEquals(
            Files.readAllBytes(picked),
            state.picture?.data,
            "the tag keeps the file's own bytes, not a re-encode",
        )
        assertEquals(
            picked.toFile().absolutePath,
            state.coverUri,
            "the display source is a path String: `CoverSourceType` resolves it to FILE_PATH, and a File " +
                "here would add a fourth kind of source to that switch",
        )
        assertTrue(state.isEditing)
    }

    @Test
    fun `a picked image that is not there is refused and logged`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })
        val before = viewModel.uiState.value.picture

        val missing = musicDir.resolve("gone.png")
        viewModel.updateCover(missing.toFile())

        awaitUntil(
            describe = { log.entries.map { it.message } },
            condition = { "Failed to read image source" in it },
        )
        assertEquals(
            "Source file: ${missing.toFile().absolutePath}",
            log.entries.single { it.message == "Failed to read image source" }.detail,
            "the log has to say which file could not be read",
        )
        assertEquals(before, viewModel.uiState.value.picture, "a cover that could not be read must not be attached")
        assertFalse(viewModel.uiState.value.isEditing, "and it must not mark the song as edited either")
    }

    @Test
    fun `a cropped image is encoded as JPEG, exported as one, and saved into the tag`() = runBlocking<Unit> {
        val path = copyFixture("bladeenc.mp3")
        val song = addSong(path)
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        val cropped = solidImageBitmap(8)
        viewModel.updateCover(cropped)
        awaitUntil(describe = { viewModel.uiState.value.coverUri }, condition = { it === cropped })

        val state = viewModel.uiState.value
        val croppedPicture = assertNotNull(state.picture, "the cropped image has to reach the tag immediately")
        val croppedBytes = croppedPicture.data
        assertEquals("image/jpeg", croppedPicture.mimeType)
        assertSame(cropped, state.coverUri, "the editor shows the crop itself, not a re-decoded copy of it")
        assertEquals(
            8,
            assertNotNull(ImageIO.read(ByteArrayInputStream(croppedBytes)), "the bytes in the tag have to be a real JPEG").width,
            "the crop is what the user saw; a quality-0.75 re-encode would come back smaller and softer",
        )

        // IMAGE_BITMAP arm of the cover-source switch: with the crop still being the display source, an
        // export has to encode it rather than copy bytes.
        val exported = musicDir.resolve("cover.jpg").toFile()
        viewModel.exportCover(exported)
        awaitUntil(describe = { viewModel.uiState.value.exportCoverResult }, condition = { it != null })
        assertTrue(viewModel.uiState.value.exportCoverResult!!, "the export has to report that it wrote something")
        assertEquals(8, assertNotNull(ImageIO.read(exported)).width)

        viewModel.saveMetadata()
        awaitUntil(describe = { viewModel.uiState.value.saveSuccess }, condition = { it == true })

        val stored = assertNotNull(readTags(song.uri).pictures.frontCoverOrFallback(), "the saved file must carry a front cover")
        assertEquals("image/jpeg", stored.mimeType)
        assertContentEquals(
            croppedBytes,
            stored.data,
            "TagLib stores the picture bytes verbatim, so the saved cover is exactly what was encoded",
        )
        assertNull(viewModel.uiState.value.saveFailureMessage)
    }

    @Test
    fun `exporting the cover next to the audio writes it under the song's name`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        val picked = writeTestImage("picked.png", "png")
        viewModel.updateCover(picked.toFile())
        awaitUntil(
            describe = { viewModel.uiState.value.coverUri },
            condition = { it == picked.toFile().absolutePath },
        )

        assertEquals("bladeenc.jpg", viewModel.getCoverFileName(), "the sibling is named after the song, always as jpg")

        viewModel.exportCoverToAudioDirectory()
        awaitUntil(describe = { viewModel.uiState.value.exportCoverResult }, condition = { it == true })

        val sibling = musicDir.resolve("bladeenc.jpg")
        assertTrue(Files.isRegularFile(sibling), "the export lands beside the audio file, not in a gallery")
        assertContentEquals(
            Files.readAllBytes(picked),
            Files.readAllBytes(sibling),
            "FILE_PATH arm: a picked file is copied, not re-encoded",
        )
    }

    // ----------------------------------------------------------- artist posters

    @Test
    fun `an artist poster is stored with its artist as description and exported as it is`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        // The artist field is what decides who the poster belongs to, so it is set here rather than read
        // from the fixture: the test is about the poster, not about the corpus file's tags.
        viewModel.updateTag { copy(artist = "An Artist") }

        val picked = writeTestImage("poster.png", "png")
        viewModel.setArtistImage(picked.toFile(), "An Artist")
        awaitUntil(
            describe = { viewModel.uiState.value.editingTagData?.pictures?.count { it.description == "An Artist" } },
            condition = { it == 1 },
        )

        val poster = viewModel.uiState.value.editingTagData!!.pictures
            .single { it.pictureType == AudioPictureType.Artist.tagLibName && it.description == "An Artist" }
        assertContentEquals(
            Files.readAllBytes(picked),
            poster.data,
            "the poster keeps the picked file's own bytes, and the description is what ties it to one artist",
        )

        val destination = musicDir.resolve("poster-out.png").toFile()
        viewModel.exportArtistImage(poster, destination)
        awaitUntil(describe = { viewModel.uiState.value.exportArtistImageResult }, condition = { it != null })
        assertTrue(viewModel.uiState.value.exportArtistImageResult!!)
        assertContentEquals(Files.readAllBytes(picked), Files.readAllBytes(destination.toPath()))
        assertTrue(
            viewModel.getArtistImageFileName().matches(Regex("""Artist_\d+\.jpg""")),
            "the save dialog's default name keeps Android's `Artist_<timestamp>.jpg`",
        )
    }

    // ------------------------------------------------------------------ saving

    @Test
    fun `saving writes the edit to the file and to the index row`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        viewModel.updateTag { copy(title = "A New Title") }
        viewModel.saveMetadata()
        awaitUntil(describe = { viewModel.uiState.value.saveSuccess }, condition = { it == true })

        assertEquals(
            "A New Title",
            readTags(song.uri).title,
            "the file is what a player shows, so the write has to land in the file itself",
        )
        assertEquals(
            "A New Title",
            library.database.songDao().getSongByUri(song.uri)?.title,
            "and the row has to follow, or the library keeps showing the old title",
        )
        val state = viewModel.uiState.value
        assertFalse(state.isEditing, "a successful save leaves editing mode")
        assertEquals(state.originalTagData, state.editingTagData, "and the snapshot becomes what was written")
    }

    @Test
    fun `a field the user hid is copied back from the file instead of being written`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        editFields.setEnabled("title", false)

        val viewModel = viewModel()
        awaitUntil(
            describe = { viewModel.visibleFields.value.map { it.code } },
            condition = { "title" !in it },
        )
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        val titleOnDisk = readTags(song.uri).title
        viewModel.updateTag {
            copy(title = "Hidden Edit", artist = "Visible Artist")
        }
        viewModel.saveMetadata()
        awaitUntil(describe = { viewModel.uiState.value.saveSuccess }, condition = { it == true })

        val saved = readTags(song.uri)
        assertEquals(
            titleOnDisk,
            saved.title,
            "a field the user has hidden is not the editor's to write: the file keeps what it had",
        )
        assertEquals(
            "Visible Artist",
            saved.artist,
            "while the fields that are visible still go through, in the same write",
        )
    }

    @Test
    fun `a save that the filesystem refuses is reported and logged`() = runBlocking<Unit> {
        val path = copyFixture("bladeenc.mp3")
        val song = addSong(path)
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        val titleBefore = readTags(song.uri).title
        viewModel.updateTag { copy(title = "Cannot Be Written") }
        path.toFile().setWritable(false)
        try {
            viewModel.saveMetadata()
            awaitUntil(
                describe = { log.entries.map { it.message } },
                condition = { entry -> entry.any { it.startsWith("Failed to save metadata:") } },
            )
            awaitUntil(describe = { viewModel.uiState.value.saveSuccess }, condition = { it == false })

            val state = viewModel.uiState.value
            assertEquals(song.uri, log.entries.first { it.message.startsWith("Failed to save metadata:") }.relatedId)
            assertNotNull(state.saveFailureMessage, "the screen shows the reason, so it has to be in the state")
            assertNotNull(state.saveFailureLogText, "and the same reason is what the user can copy out of the log")
            assertFalse(state.isSaving)
            assertEquals(
                titleBefore,
                readTags(song.uri).title,
                "a refused save must not be half-applied: the file still holds the old title",
            )
        } finally {
            // Windows refuses to delete a read-only file, so the attribute has to go back before tearDown.
            path.toFile().setWritable(true)
        }
    }

    // ----------------------------------------------------------- custom fields

    @Test
    fun `a custom field the user adds is registered so that saving writes it`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        viewModel.addCustomFieldAndShow("MYFIELD", "calm")
        // Without the registration, `filterHiddenEditFields` sees the field as hidden and copies the
        // original (empty) list back over it -- the value would silently never reach the file.
        awaitUntil(
            describe = { viewModel.visibleFields.value.filter { it.custom }.map { it.code } },
            condition = { it.isNotEmpty() },
        )

        val key = viewModel.uiState.value.editingTagData!!.customFields.single().key
        assertEquals("calm", viewModel.uiState.value.editingTagData!!.customFields.single().value)

        viewModel.saveMetadata()
        awaitUntil(describe = { viewModel.uiState.value.saveSuccess }, condition = { it == true })

        val stored = readTags(song.uri).customFields.filter { it.key == key }
        assertEquals(
            listOf("calm"),
            stored.map { it.value },
            "custom fields live in the file's own tag frame, so they have to be read back off disk",
        )
    }

    @Test
    fun `removing a custom field drops it and reverting takes the file's value back`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        viewModel.addCustomFieldAndShow("MYFIELD", "calm")
        awaitUntil(
            describe = { viewModel.visibleFields.value.filter { it.custom }.map { it.code } },
            condition = { it.isNotEmpty() },
        )
        val key = viewModel.uiState.value.editingTagData!!.customFields.single().key
        viewModel.saveMetadata()
        awaitUntil(describe = { viewModel.uiState.value.saveSuccess }, condition = { it == true })

        // A fresh read, so `originalTagData` really carries the saved field: reverting has something to
        // revert *to*.
        viewModel.readMetadata(song.uri)
        awaitUntil(
            describe = { viewModel.uiState.value.originalTagData?.customFields?.map { it.value } },
            condition = { it == listOf("calm") },
        )

        viewModel.updateCustomFieldValue(key, "loud")
        assertEquals(listOf("loud"), viewModel.uiState.value.editingTagData?.customFields?.map { it.value })

        viewModel.removeCustomFieldValue(key)
        assertTrue(
            viewModel.uiState.value.editingTagData?.customFields.orEmpty().isEmpty(),
            "removing the field clears what the user typed",
        )

        viewModel.updateCustomFieldValue(key, "loud")
        viewModel.revertCustomField(key)
        assertEquals(
            listOf("calm"),
            viewModel.uiState.value.editingTagData?.customFields?.map { it.value },
            "reverting is not the same as clearing: it puts the file's own value back",
        )
    }

    // ------------------------------------------------------------ search result

    @Test
    fun `a search result is applied to the fields it targets without touching the cover`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })
        val coverBefore = viewModel.uiState.value.coverUri

        viewModel.updateMetadataFromSearchResult(
            LyricsSearchResult(
                title = "From The Plugin",
                artist = "Plugin Artist",
                album = null,
                lyrics = "[00:01.00]Found",
                date = null,
                trackerNumber = null,
                picUrl = null,
                pluginId = "test-plugin",
                applyTargets = setOf(
                    MetadataFieldTarget.TITLE,
                    MetadataFieldTarget.ARTIST,
                    MetadataFieldTarget.LYRICS,
                ),
            )
        )

        val state = viewModel.uiState.value
        assertEquals("From The Plugin", state.editingTagData?.title)
        assertEquals("Plugin Artist", state.editingTagData?.artist)
        assertEquals("[00:01.00]Found", state.editingTagData?.lyrics)
        assertEquals(
            coverBefore,
            state.coverUri,
            "the result targets no cover, so the editor must not start pointing at a URL it never got",
        )
        assertTrue(state.isEditing, "applying a search result is an edit the user still has to save")
    }

    // ------------------------------------------------------------------ lyrics

    @Test
    fun `the lyrics offset shifts from the snapshot, so repeated shifts do not accumulate`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        val lyrics = "[00:01.00]First\n[00:02.00]Second"
        viewModel.updateTag { copy(lyrics = lyrics) }
        viewModel.prepareLyricsOffset()

        viewModel.applyLyricsOffset(1_000L)
        val once = viewModel.uiState.value.editingTagData?.lyrics.orEmpty()
        assertTrue(once.contains("[00:02.000]First"), "one second later: $once")
        assertTrue(once.contains("[00:03.000]Second"), "one second later: $once")
        assertEquals(1_000L, viewModel.currentShiftOffset.value)

        viewModel.applyLyricsOffset(2_000L)
        val twice = viewModel.uiState.value.editingTagData?.lyrics.orEmpty()
        assertTrue(
            twice.contains("[00:03.000]First"),
            "the second shift is measured from the snapshot, not from the first result: $twice",
        )
        assertTrue(twice.contains("[00:04.000]Second"), "and the same for every other line: $twice")
        assertEquals(2_000L, viewModel.currentShiftOffset.value)

        viewModel.resetLyricsOffset()
        assertEquals(lyrics, viewModel.uiState.value.editingTagData?.lyrics, "reset goes back to the snapshot itself")
        assertEquals(0L, viewModel.currentShiftOffset.value)
    }

    @Test
    fun `exporting the lyrics writes a real file and refuses an empty lyric`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        val lyrics = "[00:01.00]Line"
        viewModel.updateTag { copy(lyrics = lyrics) }
        assertEquals("bladeenc.lrc", viewModel.getLyricsFileName(), "the save dialog's default name is the song's")

        val destination = musicDir.resolve("exported.lrc").toFile()
        viewModel.exportLyrics(destination)
        awaitUntil(describe = { viewModel.uiState.value.exportLyricsResult }, condition = { it == true })
        assertEquals(lyrics, destination.readText(Charsets.UTF_8))

        viewModel.updateTag { copy(lyrics = "   ") }
        val second = musicDir.resolve("empty.lrc").toFile()
        viewModel.clearExportLyricsStatus()
        viewModel.exportLyrics(second)
        awaitUntil(describe = { viewModel.uiState.value.exportLyricsResult }, condition = { it == false })

        assertFalse(Files.exists(second.toPath()), "there is nothing to write, so no file may be created")
        assertTrue(
            log.entries.any {
                it.message == "Failed to export lyrics: lyrics are empty" && it.level == AppLogLevel.WARNING
            },
            "an export the user asked for that produced nothing has to be explained in the log",
        )
    }

    @Test
    fun `exporting the lyrics next to the audio writes a sibling lrc`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        val lyrics = "[00:01.00]Line\n[00:02.00]Next"
        viewModel.updateTag { copy(lyrics = lyrics) }

        viewModel.exportLyricsToAudioDirectory()
        awaitUntil(describe = { viewModel.uiState.value.exportLyricsResult }, condition = { it == true })

        val sibling = musicDir.resolve("bladeenc.lrc")
        assertTrue(Files.isRegularFile(sibling), "the .lrc lands next to the audio file")
        assertEquals(lyrics, Files.readString(sibling))
    }

    @Test
    fun `importing lyrics reads a real file, and an empty one is refused`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        val lyrics = "[00:05.00]Imported"
        val source = musicDir.resolve("in.lrc")
        Files.writeString(source, lyrics)

        viewModel.importLyrics(source.toFile())
        awaitUntil(describe = { viewModel.uiState.value.importLyricsResult }, condition = { it == true })
        assertEquals(lyrics, viewModel.uiState.value.editingTagData?.lyrics)
        assertTrue(viewModel.uiState.value.isEditing, "an import is an edit the user still has to save")

        val empty = musicDir.resolve("blank.lrc")
        Files.writeString(empty, "   \n")
        viewModel.clearImportLyricsStatus()
        viewModel.importLyrics(empty.toFile())
        awaitUntil(describe = { viewModel.uiState.value.importLyricsResult }, condition = { it == false })
        assertEquals(
            lyrics,
            viewModel.uiState.value.editingTagData?.lyrics,
            "a refused import must not clear the lyrics that were already there",
        )
    }

    // -------------------------------------------------------------- replay gain

    @Test
    fun `calculating ReplayGain on a real file fills the track tags and reports success`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.editingTagData }, condition = { it != null })

        viewModel.calculateReplayGain()
        awaitUntil(
            timeoutMillis = 30_000L,
            describe = { viewModel.uiState.value.replayGainScanMessage },
            condition = { it != null },
        )

        assertEquals(
            Res.string.replay_gain_calculate_success,
            (viewModel.uiState.value.replayGainScanMessage as UiMessage.Localized?)?.res,
            "real ffmpeg decoding is what produces these numbers, so a failure here is a real one",
        )
        val data = assertNotNull(viewModel.uiState.value.editingTagData)
        assertTrue(assertNotNull(data.replayGainTrackGain).endsWith(" dB"), "gain is written as '<value> dB'")
        assertNotNull(data.replayGainTrackPeak, "the peak is written next to the gain")
        assertNotNull(data.replayGainReferenceLoudness, "and the reference loudness that produced the gain")
        assertFalse(viewModel.uiState.value.isReplayGainCalculating)
        assertEquals(1f, viewModel.uiState.value.replayGainCalculateProgress)
        assertTrue(viewModel.uiState.value.isEditing, "the measured tags are the user's to review before saving")
    }

    @Test
    fun `calculating ReplayGain for a file that is not there reports the decoder's reason`() = runBlocking<Unit> {
        val viewModel = viewModel()

        // No row and no file. The read either comes back empty (the lenient path keeps a song whose
        // file is gone) or is reported as a failure; this test is not about which, only about the path
        // being remembered, so it waits for the read to have settled either way.
        val missing = musicDir.resolve("gone.mp3")
        viewModel.readMetadata(missing.toString())
        awaitUntil(
            describe = { viewModel.uiState.value.songInfo != null || log.exceptions.isNotEmpty() },
            condition = { it },
        )

        // The scan decodes the remembered path, which is what makes this a test of the *decoder's*
        // failure rather than of a path that was never read.
        viewModel.calculateReplayGain()
        awaitUntil(
            timeoutMillis = 30_000L,
            describe = { viewModel.uiState.value.replayGainScanMessage },
            condition = { it != null },
        )

        val message = viewModel.uiState.value.replayGainScanMessage as UiMessage.Localized
        assertEquals(
            Res.string.replay_gain_error_general,
            message.res,
            "a path that is not there is a library problem, not a codec problem",
        )
        assertTrue(
            message.args.firstOrNull()?.toString().orEmpty().isNotEmpty(),
            "the decoder's own message is the actionable part, so it must be carried through",
        )
        assertTrue(
            log.entries.any { it.message == "ReplayGain scan failed" && it.detail?.contains(missing.toString()) == true },
            "a failed scan has to be explained in the log, with the file it was scanning",
        )
        assertFalse(viewModel.uiState.value.isReplayGainCalculating)
        assertNull(viewModel.uiState.value.replayGainCalculateProgress, "a failed scan leaves no progress behind")
    }

    // ------------------------------------------------------------ same album

    @Test
    fun `loading a same-album cover takes the cover of another song in the album`() = runBlocking<Unit> {
        val first = addSong(copyFixture("bladeenc.mp3", "first.mp3"))
        val second = addSong(copyFixture("bladeenc.mp3", "second.mp3"))
        // The album has to be in the *files* as well as the rows: the query is built from the editing
        // state, which comes from the file's tags.
        for (song in listOf(first, second)) {
            overwrite(song.uri, AudioTagData(album = "Same Album", artist = "Same Artist"))
        }

        val viewModel = viewModel()
        // One song gets a cover through the editor's own path, so the sibling's cover is one this app
        // wrote rather than something the fixture happened to carry.
        viewModel.readMetadata(first.uri)
        awaitUntil(describe = { viewModel.uiState.value.songInfo?.uriString }, condition = { it == first.uri })
        viewModel.updateCover(solidImageBitmap(8))
        awaitUntil(describe = { viewModel.uiState.value.coverUri }, condition = { it is ImageBitmap })
        viewModel.saveMetadata()
        awaitUntil(describe = { viewModel.uiState.value.saveSuccess }, condition = { it == true })

        viewModel.readMetadata(second.uri)
        awaitUntil(describe = { viewModel.uiState.value.songInfo?.uriString }, condition = { it == second.uri })

        viewModel.loadSameAlbumCovers()
        awaitUntil(describe = { viewModel.uiState.value.sameAlbumCoverMessage }, condition = { it != null })

        assertEquals(
            Res.string.msg_same_album_cover_applied,
            (viewModel.uiState.value.sameAlbumCoverMessage as UiMessage.Localized).res,
            "the album has a cover and the current song is not the one it came from",
        )
        assertTrue(
            viewModel.uiState.value.coverUri is ImageBitmap,
            "the sibling's cover arrives as bytes, so it is decoded for display (the ByteArray arm)",
        )
    }

    // -------------------------------------------------------------------- play

    @Test
    fun `playing hands the song's path to the shell and stays quiet`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.songInfo?.uriString }, condition = { it == song.uri })

        viewModel.play()

        assertEquals(
            listOf(Path.of(song.uri).toFile()),
            openedFiles,
            "Android sent an ACTION_VIEW for the song; the desktop hands the file to the system association",
        )
        assertNull(viewModel.uiState.value.playMessage, "a play that worked has nothing to report")
    }

    @Test
    fun `playing a song whose file is gone reports no_player_found`() = runBlocking<Unit> {
        val path = copyFixture("bladeenc.mp3")
        val song = addSong(path)
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.songInfo?.uriString }, condition = { it == song.uri })

        Files.delete(path)
        viewModel.play()

        assertEquals(
            Res.string.no_player_found,
            (viewModel.uiState.value.playMessage as UiMessage.Localized?)?.res,
            "Android collapsed 'no app can open this' into this one string, and so does the port",
        )
        assertTrue(openedFiles.isEmpty(), "the shell must not be asked to open something that is not there")
    }

    @Test
    fun `a refused shell call reports unknown_error together with the reason`() = runBlocking<Unit> {
        val song = addSong(copyFixture("bladeenc.mp3"))
        val viewModel = viewModel()
        viewModel.readMetadata(song.uri)
        awaitUntil(describe = { viewModel.uiState.value.songInfo?.uriString }, condition = { it == song.uri })

        openFailure = IOException("no association for .mp3")
        viewModel.play()

        val message = viewModel.uiState.value.playMessage as UiMessage.Localized
        assertEquals(Res.string.unknown_error, message.res)
        assertEquals(
            listOf("no association for .mp3"),
            message.args.toList(),
            "the shell's message is what makes unknown_error actionable, so it must survive the port",
        )
    }

    // ----------------------------------------------------------------- helpers

    /** A real file copied out of the TagLib corpus, plus its indexed row. */
    private fun copyFixture(fixture: String, fileName: String = fixture): Path {
        val source = Path.of(System.getProperty(FIXTURES_DIR_PROPERTY), fixture)
        assertTrue(
            Files.isRegularFile(source),
            "missing audio fixture ${source.toAbsolutePath()} -- set $FIXTURES_DIR_PROPERTY",
        )
        val target = musicDir.resolve(fileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /**
     * Files a row for a real file, the way the scanner does -- sort keys included.
     *
     * The row is what makes `readMetadata` display the *path's* name and what
     * `getSongsByAlbum` looks the siblings up in, so it cannot be skipped.
     */
    private suspend fun addSong(
        path: Path,
        album: String = "Album One",
        artist: String = "An Artist",
    ): SongEntity {
        val uri = path.toRealPath().toString()
        val song = SortKeyUpdater().update(
            SongEntity(
                folderId = requireNotNull(rootFolderId ?: rootFolder()),
                mediaId = 0,
                source = SongSource.LOCAL,
                filePath = uri,
                fileName = path.fileName.toString(),
                fileSize = Files.size(path),
                fileLastModified = Files.getLastModifiedTime(path).toMillis(),
                title = "Title of ${path.fileName}",
                album = album,
                artist = artist,
                albumArtist = artist,
                uri = uri,
            )
        )
        library.database.songDao().insert(song)
        return requireNotNull(library.database.songDao().getSongByUri(uri))
    }

    private suspend fun rootFolder(): Long {
        val id = library.database.folderDao().insert(
            FolderEntity(path = musicDir.toRealPath().toString(), addedBySaf = true)
        )
        rootFolderId = id
        return id
    }

    /** A real image file on disk, in the format the extension claims. */
    private fun writeTestImage(name: String, format: String): Path {
        val path = musicDir.resolve(name)
        assertTrue(
            ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), format, path.toFile()),
            "the test needs a real $format file",
        )
        return path
    }

    /** What the crop sheet would hand back: an 8x8 image the encoder has to turn into JPEG bytes. */
    private fun solidImageBitmap(size: Int): ImageBitmap {
        val bitmap = ImageBitmap(size, size)
        Canvas(bitmap).drawRect(
            left = 0f,
            top = 0f,
            right = size.toFloat(),
            bottom = size.toFloat(),
            paint = Paint().apply { color = Color.Red },
        )
        return bitmap
    }

    /** Polls [condition] until it holds, reporting [describe] if it never does. */
    private suspend fun <T> awaitUntil(
        timeoutMillis: Long = 5_000L,
        describe: () -> T,
        condition: (T) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val observed = describe()
            if (condition(observed)) return
            kotlinx.coroutines.delay(25L)
        }
        throw AssertionError("condition was never met within ${timeoutMillis}ms; last value ${describe()}")
    }

    private companion object {
        const val FIXTURES_DIR_PROPERTY = "lyrico.audiotag.fixtures.dir"
    }
}
