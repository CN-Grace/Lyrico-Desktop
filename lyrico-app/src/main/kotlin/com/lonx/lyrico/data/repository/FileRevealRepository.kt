package com.lonx.lyrico.data.repository

import com.lonx.lyrico.platform.ExplorerFileRevealer
import com.lonx.lyrico.platform.FileRevealer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Handing songs over to Explorer, which is what "share" means on desktop (PLAN.md decision).
 *
 * Android's version of this action was `Intent.ACTION_SEND` / `ACTION_SEND_MULTIPLE` with the audio
 * `Uri`s attached, and it shared **every** selected song at once. Explorer's `/select,` switch takes
 * exactly one path, so a multi-song share reveals the first one that exists — the folder opens with a
 * file selected instead of with N files selected. That is a real reduction in what the action can
 * express, not an implementation detail, so it is stated here and returned as data rather than
 * silently dropping the rest.
 *
 * Like every other repository in the port, this returns a result instead of drawing anything: the
 * messages the user sees are chosen by the UI.
 */
interface FileRevealRepository {

    /**
     * Opens Explorer at the folder of the first existing file in [paths], with that file selected.
     *
     * Non-existent paths are skipped rather than failing the whole request, because the caller's list
     * comes from the database and can legitimately contain a file that was removed outside the app;
     * if *none* of them exist, [RevealResult.FilesUnavailable] says so.
     */
    fun reveal(paths: List<Path>): RevealResult
}

sealed interface RevealResult {

    /** Explorer was asked to show [path]. */
    data class Revealed(val path: Path) : RevealResult

    /** Nothing to do: the caller passed an empty list. */
    data object NothingToReveal : RevealResult

    /** None of the requested paths is an existing regular file. */
    data class FilesUnavailable(val paths: List<Path>) : RevealResult

    /** The shell refused to launch. */
    data class Failed(val path: Path, val throwable: Throwable) : RevealResult
}

class FileRevealRepositoryImpl(
    private val fileRevealer: FileRevealer = ExplorerFileRevealer(),
) : FileRevealRepository {

    override fun reveal(paths: List<Path>): RevealResult {
        if (paths.isEmpty()) return RevealResult.NothingToReveal

        val existing = paths
            .map { it.toAbsolutePath().normalize() }
            .firstOrNull { Files.isRegularFile(it) }
            ?: return RevealResult.FilesUnavailable(paths)

        return try {
            fileRevealer.reveal(existing.toFile())
            RevealResult.Revealed(existing)
        } catch (e: Exception) {
            RevealResult.Failed(existing, e)
        }
    }
}
