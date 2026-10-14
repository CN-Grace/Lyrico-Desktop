package com.lonx.lyrico.platform

import com.lonx.lyrico.utils.logging.PlatformLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * The single-instance guard: one running Lyrico per data folder.
 *
 * Android never needed this -- the launcher brings the existing task to the front instead of starting a
 * second copy. Windows will start as many copies as the user asks for, and two copies of this app is not
 * a cosmetic problem: they share one Room database and one cover cache, they each run the start-up
 * maintenance that marks orphaned batch tasks as failed, and both scanners can write the same rows. The
 * second copy also has no way to show the user anything useful, so the honest answer is to refuse to
 * start and say why.
 *
 * The lock is an OS-level exclusive `FileLock` on a file **inside the data folder**, which is what makes
 * the scope right: a portable install and an installed copy with separate data folders are two separate
 * libraries and may both run, while a second launch of the same one cannot.
 */
class InstanceLock(private val file: File) {

    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /**
     * Takes the lock, or reports that somebody else has it.
     *
     * Returns true when the lock could not even be attempted (a read-only or missing data folder): the
     * caller is already in trouble in that case, and refusing to open the window would turn a
     * permissions problem into a silent failure to launch. Only a lock that is definitely held by
     * another process -- or by this one, twice -- returns false.
     */
    fun tryAcquire(): Boolean {
        release()
        return try {
            file.parentFile?.mkdirs()
            val opened = RandomAccessFile(file, "rw").channel
            channel = opened
            val acquired = opened.tryLock()
            if (acquired == null) {
                // Another process holds it. `tryLock` returning null and throwing are different answers,
                // so this is the branch that actually means "already running".
                release()
                false
            } else {
                lock = acquired
                true
            }
        } catch (_: OverlappingFileLockException) {
            // The same JVM asking twice, which in practice means a test.
            release()
            false
        } catch (error: IOException) {
            PlatformLog.w(LOG_TAG, "无法锁定数据目录 ${file.parentFile}，跳过单实例检查", error)
            release()
            true
        }
    }

    /** Releases the lock. Called when the window closes; the OS would also do it at process exit. */
    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }

    private companion object {
        const val LOG_TAG = "InstanceLock"
    }
}
