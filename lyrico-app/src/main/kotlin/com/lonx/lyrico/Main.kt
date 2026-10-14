package com.lonx.lyrico

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.lonx.audiotag.internal.NativeLibraryLoader
import com.lonx.lyrico.data.SharedSelectionManager
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.di.desktopAppModule
import com.lonx.lyrico.platform.AppDirectories
import com.lonx.lyrico.platform.InstanceLock
import com.lonx.lyrico.platform.WindowGeometry
import com.lonx.lyrico.platform.WindowGeometryStore
import com.lonx.lyrico.platform.clampToScreen
import com.lonx.lyrico.ui.EscapeBackKey
import com.lonx.lyrico.ui.SelectionModeShortcuts
import com.lonx.lyrico.utils.logging.PlatformLog
import com.lonx.lyrico.utils.coil.installImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JOptionPane
import kotlin.math.roundToInt

private const val LOG_TAG = "Lyrico"

/** The size a first run opens at, and the smallest the window may be dragged. */
private const val DEFAULT_WINDOW_WIDTH = 1180
private const val DEFAULT_WINDOW_HEIGHT = 780
private const val MINIMUM_WINDOW_WIDTH = 640
private const val MINIMUM_WINDOW_HEIGHT = 480

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

    // Before anything else touches the database: two copies of this app share one Room database, one
    // cover cache and one set of batch tasks, so a second copy is not a harmless extra window. The lock
    // is taken before the DI graph starts, because starting it opens the database.
    val instanceLock = InstanceLock(directories.instanceLockFile)
    if (!instanceLock.tryAcquire()) {
        PlatformLog.w(LOG_TAG, "已有实例在运行，数据目录=${directories.root}")
        JOptionPane.showMessageDialog(
            null,
            "Lyrico 已经在运行。\n\n数据目录：${directories.root}\n\n两个实例会同时读写同一个音乐库，" +
                "请切换到已打开的窗口，或先关闭它再启动。",
            "Lyrico 已在运行",
            JOptionPane.INFORMATION_MESSAGE,
        )
        return
    }

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
        // `Esc` is a back request for whatever overlay is in front (a Miuix sheet or dialog), and the
        // framework never raises one on the desktop -- see [EscapeBackKey]. The key is created here, but
        // it is *attached* inside the window: the dispatcher is a host default, and a host default only
        // exists inside a host's content (reading it from the application block throws).
        val escapeBackKey = remember { EscapeBackKey() }

        // The keyboard half of the desktop selection mode (Esc / Ctrl+A). Resolved here rather than
        // from a screen because it belongs to the window, and because the handler must be the one
        // `Window` calls *after* the focused component has had its chance -- see [SelectionModeShortcuts].
        val selectionShortcuts = remember {
            SelectionModeShortcuts(
                selectionManager = GlobalContext.get().get<SharedSelectionManager>(),
                escapeBackKey = escapeBackKey,
            )
        }

        // Android handed the Activity a window and forgot about it. Here the geometry is the user's, so
        // it is restored on the way in and written back on the way out.
        val geometryStore = remember { WindowGeometryStore(directories.windowStateFile) }
        val savedGeometry = remember { geometryStore.load() }
        val windowState = rememberWindowState(
            position = savedGeometry
                ?.let { WindowPosition(it.x.dp, it.y.dp) }
                ?: WindowPosition.PlatformDefault,
            size = savedGeometry
                ?.let { DpSize(it.width.dp, it.height.dp) }
                ?: DpSize(DEFAULT_WINDOW_WIDTH.dp, DEFAULT_WINDOW_HEIGHT.dp),
        )

        Window(
            onCloseRequest = {
                geometryStore.save(windowState.geometry())
                instanceLock.release()
                exitApplication()
            },
            onKeyEvent = selectionShortcuts::onKeyEvent,
            title = "Lyrico ${BuildInfo.VERSION_NAME} (${BuildInfo.COMMIT})",
            state = windowState,
        ) {
            // Windows' equivalent of Android's minimum touch target: without this the window can be
            // dragged down to a few pixels, where the adaptive layout has nothing left to arrange. The
            // floor is deliberately below the compact layout's breakpoint, so the narrow layout stays
            // reachable by dragging rather than only on a small screen.
            window.minimumSize = with(LocalDensity.current) {
                Dimension(MINIMUM_WINDOW_WIDTH.dp.roundToPx(), MINIMUM_WINDOW_HEIGHT.dp.roundToPx())
            }

            // A position that is no longer on any screen is fixed up here rather than before the window
            // exists, because this is where both numbers are known in the same unit: the window's own
            // location and the screens' bounds are device pixels straight from AWT, with no density
            // conversion in between that could be wrong by a scale factor.
            LaunchedEffect(Unit) { keepWindowOnScreen(window) }

            // The one thing that has to happen *inside* the window: the back dispatcher the overlays and
            // the navigation stack register their handlers on is a host default, and the host is the
            // window. Attaching it here is what lets `Esc` reach a Miuix sheet -- see [EscapeBackKey].
            val backDispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
            DisposableEffect(escapeBackKey, backDispatcher) {
                val unregister = backDispatcher?.let { escapeBackKey.attachTo(it) }
                onDispose { unregister?.invoke() }
            }

            // Windows' equivalent of Android's `Intent` extra: when the app is started through a
            // file association ("open with"), the file is the first command-line argument.
            LyricoDesktopApp(externalFilePath = args.firstOrNull()?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * The window's geometry as [WindowState] reports it, which is the same unit `rememberWindowState`
 * accepts -- so a saved value restores to the size and place it was saved from.
 */
private fun WindowState.geometry(): WindowGeometry = WindowGeometry(
    x = position.x.value.roundToInt(),
    y = position.y.value.roundToInt(),
    width = size.width.value.roundToInt(),
    height = size.height.value.roundToInt(),
)

/**
 * Moves a window whose saved position is no longer reachable back onto a screen.
 *
 * The case: the second monitor was unplugged, or the display layout changed, and the saved position now
 * points at nothing. Windows restores the window there anyway, where the user can neither see it nor
 * drag it back -- and because the geometry is saved again on close, the app would stay lost.
 *
 * Nothing happens in the normal case, so this cannot move a window the user deliberately placed. Both
 * sides of the comparison are device pixels from AWT, which is what makes the check exact.
 */
private fun keepWindowOnScreen(window: java.awt.Window) {
    val bounds = virtualScreenBounds() ?: return
    val location = window.location
    val clamped = WindowGeometry(
        x = location.x,
        y = location.y,
        width = window.width,
        height = window.height,
    ).clampToScreen(bounds)

    if (clamped.x != location.x || clamped.y != location.y) {
        PlatformLog.i(LOG_TAG, "窗口位置 ${location.x},${location.y} 不在屏幕上，修正为 ${clamped.x},${clamped.y}")
        window.setLocation(clamped.x, clamped.y)
    }
}

/**
 * The bounding box of every monitor, or null when there is nothing to measure.
 *
 * The union rather than the primary screen's bounds: a window the user put on a second monitor is not
 * off-screen, and clamping it to the primary monitor would undo a deliberate placement on every start.
 */
private fun virtualScreenBounds(): Rectangle? = runCatching {
    val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
    devices.map { it.defaultConfiguration.bounds }.reduceOrNull { acc, bounds -> acc.union(bounds) }
}.getOrNull()