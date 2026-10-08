package com.lonx.audiotag.internal

/**
 * Platform-neutral logging facade for the audio tag bridge.
 *
 * The Android build logged through `android.util.Log`, which the desktop JVM does not have.
 * This is the replacement: a small, dependency-free sink that the host application can redirect
 * (e.g. into its own log file) by assigning [sink] during startup. The default writes to stdout /
 * stderr so the module stays usable stand-alone, including from plain-Java smoke tests.
 */
public enum class TagLogLevel { DEBUG, INFO, WARN, ERROR }

public object TagLog {

    /** Receives every log record. Assign to route records into an application logger. */
    @Volatile
    public var sink: (level: TagLogLevel, tag: String, message: String, error: Throwable?) -> Unit =
        ::print

    public fun d(tag: String, message: String): Unit = sink(TagLogLevel.DEBUG, tag, message, null)

    public fun i(tag: String, message: String): Unit = sink(TagLogLevel.INFO, tag, message, null)

    public fun w(tag: String, message: String, error: Throwable? = null): Unit =
        sink(TagLogLevel.WARN, tag, message, error)

    public fun e(tag: String, message: String, error: Throwable? = null): Unit =
        sink(TagLogLevel.ERROR, tag, message, error)

    private fun print(level: TagLogLevel, tag: String, message: String, error: Throwable?) {
        val line = "[$level] $tag: $message"
        if (level == TagLogLevel.ERROR) {
            System.err.println(line)
            error?.printStackTrace(System.err)
        } else {
            println(line)
        }
    }
}
