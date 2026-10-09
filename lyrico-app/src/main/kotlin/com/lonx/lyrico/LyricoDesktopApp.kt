package com.lonx.lyrico

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lonx.lyrico.ui.navigation.LyricoNavHost
import com.lonx.lyrico.ui.theme.LyricoTheme

/**
 * The desktop application, replacing `LyricoApp(externalUri: Uri?, externalEditRequestId: Long)`.
 *
 * Android's external-input parameters were about receiving an `Intent`/`Uri` from the system ("open
 * with", share sheet). Windows' equivalent is a file path on the command line, so the parameter is a
 * plain [externalFilePath]. It is currently unused and stays `null` until the edit-metadata screen is
 * ported; the window passes whatever `main` received.
 *
 * Only the navigation host and theme live here. Everything else -- data folder, DI graph, logging --
 * happens in `main` before the first frame, because a screen that cannot resolve its dependencies
 * should fail at startup with a clear message rather than as a blank window.
 */
@Composable
fun LyricoDesktopApp(externalFilePath: String? = null) {
    // The ported screens were written against the Android theme, which wraps Miuix and supplies the
    // ripple indication; the port keeps that wrapper rather than theming each screen.
    LyricoTheme {
        LyricoNavHost(modifier = Modifier.fillMaxSize())
    }
}
