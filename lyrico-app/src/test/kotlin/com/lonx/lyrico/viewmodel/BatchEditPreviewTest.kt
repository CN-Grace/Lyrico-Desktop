package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.lonx.lyrico.data.model.SongSource
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.support.HeadlessMainDispatcher
import com.lonx.lyrico.worker.processor.EditTagsTaskConfig
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two pure functions inside `BatchEditViewModel` that decide *what will actually be written*.
 *
 * Everything else in this view model is plumbing around a live selection and a running task, and the batch
 * engine covers the applying half. These two are the part that can silently corrupt a library: the preview
 * list (what the user is asked to confirm) and the task config (what the processor is handed). They are
 * `internal` rather than private for exactly this reason -- no library, no selection, no task needed.
 *
 * The distinction the tests pin is **"not touched" versus "cleared"**: a field the user did not edit must
 * arrive as `KEEP_VALUE`, while a field the user cleared must arrive as an empty string. Getting that
 * backwards would erase tags the user never meant to change, which is the worst outcome this screen can
 * produce.
 */
class BatchEditPreviewTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-batch-edit-preview").toFile()
    private val directories = AppDirectories(root = workingDir, isPortable = true).prepare()
    private val mainDispatcher = HeadlessMainDispatcher()
    private val store = ViewModelStore()

    /**
     * The view model is built by the real graph: its `init` starts observing the shared selection, so it
     * needs the real collaborators (and a main dispatcher to launch on) even though the functions under test
     * are pure.
     */
    private val provider = ViewModelProvider.create(
        store,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: kotlin.reflect.KClass<T>, extras: CreationExtras): T =
                GlobalContext.get().get(modelClass)
        },
        CreationExtras.Empty,
    )

    @BeforeTest
    fun setUp() {
        runCatching { stopKoin() }
        mainDispatcher.install()
        startKoin { modules(desktopAppModule(directories)) }
    }

    @AfterTest
    fun tearDown() {
        store.clear()
        runCatching { GlobalContext.get().get<CoroutineScope>().cancel() }
        runCatching { stopKoin() }
        mainDispatcher.restore()
        workingDir.deleteRecursively()
    }

    private val keep = EditTagsTaskConfig.KEEP_VALUE

    private fun song(
        title: String? = "旧标题",
        artist: String? = "旧艺人",
        album: String? = null,
    ) = SongEntity(
        folderId = 1L,
        mediaId = 0,
        source = SongSource.LOCAL,
        filePath = """H:\Music\song.mp3""",
        fileName = "song.mp3",
        fileSize = 1_000L,
        fileLastModified = 1_700_000_000_000L,
        title = title,
        artist = artist,
        album = album,
        durationMilliseconds = 180_000,
        uri = """H:\Music\song.mp3""",
    )

    /**
     * A state in which nothing has been touched.
     *
     * Untouched is `EditTagsTaskConfig.KEEP_VALUE` -- a sentinel, *not* the empty string: empty means "clear
     * this tag", and the whole point of the sentinel is to keep the two apart. Every test below starts here
     * and then edits exactly the fields it is about.
     */
    private fun untouchedState() = BatchEditUiState(
        title = keep,
        artist = keep,
        album = keep,
        albumArtist = keep,
    )

    // ------------------------------------------------------------------ 预览

    @Test
    fun `an edited field shows up as a change with its label`() {
        val changes = viewModel().buildPreviewChanges(
            song = song(),
            state = untouchedState().copy(title = "新标题"),
            visibleFieldCodes = setOf("title"),
        )

        val change = changes.single()
        // The label comes from the field's own resource; the values are what the preview tab prints.
        assertTrue(change.labelResId != null, "the change carries no label resource: $change")
        assertEquals(null, change.customLabel)
        assertEquals("旧标题", change.oldValue)
        assertEquals("新标题", change.newValue)
    }

    @Test
    fun `a field that was not edited produces no change`() {
        val changes = viewModel().buildPreviewChanges(
            song = song(),
            state = untouchedState(),
            visibleFieldCodes = setOf("title", "artist"),
        )

        assertEquals(emptyList(), changes, "an untouched field must not be reported as a change")
    }

    @Test
    fun `a hidden field is not previewed even when the state has a value`() {
        val changes = viewModel().buildPreviewChanges(
            song = song(),
            // The user edited the artist, but the edit-field config hides it, so the screen never offered it.
            state = untouchedState().copy(artist = "新艺人"),
            visibleFieldCodes = setOf("title"),
        )

        assertEquals(emptyList(), changes, "a hidden field reached the preview")
    }

    @Test
    fun `clearing a field is previewed as a change to empty`() {
        val changes = viewModel().buildPreviewChanges(
            song = song(title = "旧标题"),
            state = untouchedState().copy(title = ""),
            visibleFieldCodes = setOf("title"),
        )

        assertEquals("", changes.single().newValue, "clearing a tag must be offered as a change")
    }

    // ------------------------------------------------------------------ 任务配置

    @Test
    fun `fields the user did not touch are sent as keep, not as empty`() {
        val config = with(viewModel()) {
            untouchedState().copy(title = "新标题").toTaskConfig(visibleFieldCodes = setOf("title", "artist"))
        }

        assertEquals("新标题", config.title)
        assertEquals(keep, config.artist, "an untouched field was sent as a value, which would erase the tag")
        assertEquals(keep, config.album, "a field outside the visible set was sent as a value")
    }

    @Test
    fun `a field the user cleared is sent as empty, not as keep`() {
        val config = with(viewModel()) {
            untouchedState().copy(title = "").toTaskConfig(visibleFieldCodes = setOf("title"))
        }

        assertEquals("", config.title, "clearing a field has to be a write, not a keep")
        assertTrue(keep != config.title, "the cleared field was sent as keep, so clearing would do nothing")
    }

    private fun viewModel(): BatchEditViewModel = provider[BatchEditViewModel::class]
}
