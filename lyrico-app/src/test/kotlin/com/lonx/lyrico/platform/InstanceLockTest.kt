package com.lonx.lyrico.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The single-instance guard.
 *
 * A `FileLock` is held per JVM, so these tests can prove the two things a lock can get wrong on its
 * own -- that a second acquisition is refused, and that releasing hands the lock over -- but they
 * cannot prove that a *second process* is refused, because a test cannot be another process. That half
 * is checked by starting the packaged app twice, which is where the behaviour actually matters.
 *
 * The last test is the one that matters for the port: a data folder the app cannot lock must not stop
 * the app from starting, because the alternative is an installation that silently does nothing.
 */
class InstanceLockTest {

    private val workingDir: File = Files.createTempDirectory("lyrico-instance-lock").toFile()

    @AfterTest
    fun cleanUp() {
        workingDir.deleteRecursively()
    }

    private fun lockFile(name: String = "lyrico.lock") = File(workingDir, name)

    @Test
    fun `the first lock on a data folder is taken`() {
        val lock = InstanceLock(lockFile())

        try {
            assertTrue(lock.tryAcquire())
        } finally {
            lock.release()
        }
    }

    @Test
    fun `a second lock on the same data folder is refused`() {
        val first = InstanceLock(lockFile())
        val second = InstanceLock(lockFile())

        try {
            assertTrue(first.tryAcquire())

            assertFalse(second.tryAcquire())
        } finally {
            first.release()
            second.release()
        }
    }

    @Test
    fun `releasing hands the lock to the next instance`() {
        val first = InstanceLock(lockFile())
        val second = InstanceLock(lockFile())

        try {
            assertTrue(first.tryAcquire())
            first.release()

            assertTrue(second.tryAcquire())
        } finally {
            first.release()
            second.release()
        }
    }

    @Test
    fun `a second data folder is a second library and may run`() {
        val portable = InstanceLock(lockFile("portable/lyrico.lock"))
        val installed = InstanceLock(lockFile("installed/lyrico.lock"))

        try {
            assertTrue(portable.tryAcquire())

            assertTrue(installed.tryAcquire())
        } finally {
            portable.release()
            installed.release()
        }
    }

    @Test
    fun `locking the same folder twice in a row does not leak the first lock`() {
        val lock = InstanceLock(lockFile())

        try {
            assertTrue(lock.tryAcquire())
            assertTrue(lock.tryAcquire())
        } finally {
            lock.release()
        }

        val other = InstanceLock(lockFile())
        try {
            assertTrue(other.tryAcquire())
        } finally {
            other.release()
        }
    }

    @Test
    fun `a data folder that cannot be written does not block startup`() {
        // The parent is a regular file, so the lock file cannot be created at all. Refusing to start in
        // that case would turn a broken installation into an app that appears to do nothing.
        val blocked = File(workingDir, "not-a-directory").apply { writeText("a regular file") }
        val lock = InstanceLock(File(blocked, "lyrico.lock"))

        try {
            assertTrue(lock.tryAcquire())
        } finally {
            lock.release()
        }
    }

    @Test
    fun `releasing a lock that was never taken is harmless`() {
        val lock = InstanceLock(lockFile())

        lock.release()
        lock.release()
    }
}
