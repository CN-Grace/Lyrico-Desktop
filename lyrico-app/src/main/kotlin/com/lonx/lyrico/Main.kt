package com.lonx.lyrico

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lonx.audiotag.internal.NativeLibraryLoader
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.utils.logging.PlatformLog
import com.lonx.lyrico.utils.coil.installImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

private const val LOG_TAG = "Lyrico"

/**
 * Desktop entry point, replacing the Android `MainActivity`.
 *
 * The startup sequence is deliberately explicit, in this order, and every step is logged:
 *
 * 1. resolve the data folder,
 * 2. create its directories,
 * 3. load the native audio-tag libraries,
 * 4. start the DI graph,
 * 5. run start-up maintenance (trim the app log, fail tasks orphaned by a previous run),
 * 6. install the cover-art image loader,
 * 7. open the window.
 *
 * A failure in 1-4 is reported as a dialog-less crash (a non-zero exit with a stack trace) rather than
 * an empty window: there is nothing useful to show a user whose database directory is unusable. Once
 * the window is open the app is in charge of its own error reporting (`AppLogRepository`, snackbars).
 */
fun main(args: Array<String>) {
    val directories = AppDirectories.resolve()
    PlatformLog.i(
        LOG_TAG,
        "数据目录=${directories.root} (${if (directories.isPortable) "便携模式" else "用户目录回退"})",
    )
    directories.prepare()

    // Loaded before the first screen, not on first use: a missing DLL must not surface later as a
    // mysterious failure inside a scan or a tag write. The scan/tag layers load it again themselves,
    // which is a no-op after this.
    val nativeStatus = runCatching { NativeLibraryLoader.load() }.fold(
        onSuccess = {
            "native OK: ${System.getProperty(NativeLibraryLoader.NATIVE_DIR_PROPERTY) ?: "java.library.path"}"
        },
        onFailure = { error -> "native FAILED: ${error.message}" },
    )
    PlatformLog.i(LOG_TAG, nativeStatus)

    startKoin {
        modules(desktopAppModule(directories))
    }

    // Start-up maintenance, as Android's `App` did on a background scope: trim the app log to its
    // retention policy, and mark batch tasks left RUNNING by a previous process as FAILED. The
    // second one matters more on the desktop than it did on Android: a running task lives in the
    // process, so a task that was still RUNNING when the app closed has nobody left to finish it,
    // and the task list would show it as running forever. Nothing is awaited -- the window opens
    // while this runs -- but a failure is logged rather than swallowed, because a silent failure
    // here would leave exactly the stuck rows this is meant to clear.
    runCatching {
        val appScope = GlobalContext.get().get<CoroutineScope>()
        appScope.launch {
            runCatching {
                GlobalContext.get().get<AppLogRepository>().trim()
                GlobalContext.get().get<BatchTaskRepository>().markOrphanedTasksFailed()
            }.onFailure { PlatformLog.e(LOG_TAG, "启动维护失败", it) }
        }
    }.onFailure { PlatformLog.e(LOG_TAG, "启动维护无法调度", it) }

    // Covers decode through Coil, whose loader is a process-wide singleton on Android too; a missing
    // or failing cache directory must not take the app down, so the cover cache is only an
    // optimisation and its setup failure is logged rather than fatal.
    runCatching { installImageLoader(directories.coverCacheDir) }
        .onFailure { PlatformLog.e(LOG_TAG, "封面缓存目录初始化失败", it) }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Lyrico ${BuildInfo.VERSION_NAME} (${BuildInfo.COMMIT})",
            state = rememberWindowState(width = 1180.dp, height = 780.dp),
        ) {
            // Window size is unconstrained on purpose: the ported screens adapt to the available
            // width (the library home switches between a navigation rail and a bottom bar), so the
            // user is expected to resize.
            // Windows' equivalent of Android's `Intent` extra: when the app is started through a
            // file association ("open with"), the file is the first command-line argument.
            LyricoDesktopApp(externalFilePath = args.firstOrNull()?.takeIf { it.isNotBlank() })
        }
    }
}