package com.lonx.lyrico.utils.logging

/**
 * Console logging, replacing Android's `android.util.Log` in the ported code.
 *
 * Android's `Log` writes into logcat, which the platform owns; there is no equivalent on the
 * desktop, so the ported classes log to standard output. This is for developer diagnostics only —
 * anything the user should be able to look at afterwards still goes through the app's own log
 * repository (`AppLogRepository`, stored in the database).
 *
 * Android's `Log.wtf` has no counterpart here: it means "this cannot happen", which on the desktop
 * is an exception, not a log line.
 */
object PlatformLog {

    /**
     * Where the formatted lines go. Tests can replace it to assert on what a component logged; the
     * failure output of a JVM unit test is left alone when it is null.
     */
    var sink: ((level: Char, tag: String, message: String, throwable: Throwable?) -> Unit)? =
        ::writeToStdOut

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        log(DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        log(INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        log(WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(ERROR, tag, message, throwable)

    private fun log(level: Char, tag: String, message: String, throwable: Throwable?) {
        sink?.invoke(level, tag, message, throwable)
    }

    private fun writeToStdOut(level: Char, tag: String, message: String, throwable: Throwable?) {
        println("$level/$tag: $message")
        throwable?.printStackTrace()
    }

    const val DEBUG = 'D'
    const val INFO = 'I'
    const val WARN = 'W'
    const val ERROR = 'E'
}
