package com.lonx.lyrico.ui.components.song

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.utils.formattedStringResource
import com.lonx.lyrico.resources.scan_progress_finished
import com.lonx.lyrico.resources.scan_progress_listing
import com.lonx.lyrico.resources.scan_progress_reading
import com.lonx.lyrico.resources.scan_progress_writing
import com.lonx.lyrico.data.song.scan.LibraryScanProgress
import com.lonx.lyrico.data.song.scan.LibraryScanStage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LibraryScanProgressText(
    progress: LibraryScanProgress,
    modifier: Modifier = Modifier
) {
    val title = when (progress.stage) {
        LibraryScanStage.LISTING_FILES -> stringResource(Res.string.scan_progress_listing)
        LibraryScanStage.READING_METADATA -> formattedStringResource(
            Res.string.scan_progress_reading,
            progress.current,
            progress.total
        )
        LibraryScanStage.WRITING_DATABASE -> stringResource(Res.string.scan_progress_writing)
        LibraryScanStage.FINISHED -> stringResource(Res.string.scan_progress_finished)
    }

    Text(
        text = title,
        modifier = modifier
            .padding(horizontal = 24.dp),
        style = MiuixTheme.textStyles.title4,
        color = MiuixTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
}
