package com.lonx.lyrico.data.model

import com.lonx.lyrico.resources.Res
import com.lonx.lyrico.resources.batch_task_edit_tags
import com.lonx.lyrico.resources.batch_task_export_cover
import com.lonx.lyrico.resources.batch_task_export_lyrics
import com.lonx.lyrico.resources.batch_task_match_cover
import com.lonx.lyrico.resources.batch_task_match_lyrics
import com.lonx.lyrico.resources.batch_task_match_tags
import com.lonx.lyrico.resources.batch_task_rename_files
import com.lonx.lyrico.resources.batch_task_scan_replay_gain
import com.lonx.lyrico.resources.lyrics_process_title
import org.jetbrains.compose.resources.StringResource

enum class BatchTaskType(
    val labelRes: StringResource
) {
    MATCH_METADATA(Res.string.batch_task_match_tags),
    MATCH_LYRICS(Res.string.batch_task_match_lyrics),
    MATCH_COVER(Res.string.batch_task_match_cover),
    EDIT_TAGS(Res.string.batch_task_edit_tags),
    RENAME_FILES(Res.string.batch_task_rename_files),
    CONVERT_LYRICS_FORMAT(Res.string.lyrics_process_title),
    SCAN_REPLAY_GAIN(Res.string.batch_task_scan_replay_gain),
    EXPORT_LYRICS(Res.string.batch_task_export_lyrics),
    EXPORT_COVER(Res.string.batch_task_export_cover)
}
