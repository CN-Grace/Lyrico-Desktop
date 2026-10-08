package com.lonx.lyrico.data.song.scan

import com.lonx.lyrico.data.model.SongPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** The file metadata the scanner needs, so a fake filesystem does not have to invent timestamps. */
data class SongFileAttributes(
    val size: Long,
    val lastModified: Long,
    /** NTFS creation time; 0 when the filesystem does not record one. */
    val created: Long,
    val isDirectory: Boolean
)

/**
 * The filesystem operations a library scan performs, as an interface.
 *
 * The scanner is the one place in the app that has to cope with a filesystem that misbehaves:
 * a folder that was deleted, a permission-denied directory, a file that disappears between listing
 * and reading. Those are the paths worth testing, and they are the hardest to provoke for real on
 * Windows (making a directory unreadable requires ACL work), so the scanner reads the disk through
 * this seam and the tests supply the failure.
 *
 * Everything that is not about failure is deliberately thin: [attributes] returns the three numbers
 * the database stores, and paths are already canonical (`SongPaths.canonicalize`) before they reach
 * the scanner, so no method here has to re-derive casing or resolve links.
 */
interface SongFileSystem {

    /** True when [path] exists and is a directory. Never throws for a missing path. */
    fun isDirectory(path: Path): Boolean

    /** Directory entries, as full paths. Throws when the directory cannot be read. */
    fun list(path: Path): List<Path>

    /** Metadata of one entry. Throws when it cannot be read. */
    fun attributes(path: Path): SongFileAttributes

    /**
     * The real path of [path], used to detect directory cycles (a Windows junction can point at an
     * ancestor, and following it forever would never terminate). Falls back to [path] when the
     * filesystem cannot resolve it.
     */
    fun realPath(path: Path): Path
}

/** [SongFileSystem] backed by `java.nio.file`. */
class NioSongFileSystem : SongFileSystem {

    override fun isDirectory(path: Path): Boolean =
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(path)

    override fun list(path: Path): List<Path> =
        Files.newDirectoryStream(path).use { stream -> stream.toList() }

    override fun attributes(path: Path): SongFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val created = attributes.creationTime().toMillisOrZero()
        return SongFileAttributes(
            size = if (attributes.isDirectory) 0L else attributes.size(),
            lastModified = attributes.lastModifiedTime().toMillisOrZero(),
            // Windows records the real creation time; a filesystem without one reports 0 here, and
            // the scanner falls back to the modification time.
            created = created,
            isDirectory = attributes.isDirectory
        )
    }

    override fun realPath(path: Path): Path = SongPaths.canonicalize(path)

    private fun FileTime.toMillisOrZero(): Long = try {
        toMillis()
    } catch (_: IOException) {
        0L
    }
}
