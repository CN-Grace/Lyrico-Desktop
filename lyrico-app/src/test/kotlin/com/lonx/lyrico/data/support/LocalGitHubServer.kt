package com.lonx.lyrico.data.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * A real HTTP server on the loopback interface standing in for `api.github.com`.
 *
 * The GitHub-backed repositories build their own URLs (`https://api.github.com/repos/...`) and take
 * only an [OkHttpClient], so a test cannot point them at a fake server by handing over an address.
 * What it *can* hand over is a client whose interceptor rewrites the host: the production code still
 * builds its real URL, sends a real request over a real socket and parses a real response — only the
 * server is local. That is a stronger test than a mocked repository, and the JDK's own HTTP server
 * means it costs no new dependency.
 *
 * The plugin host API takes a URL instead of a client, so the plugin tests address this server
 * directly (`http://127.0.0.1:$port/...`) and never need [client]. For them the fixture records the
 * whole request — method, headers and body — and [respondBytes] can answer with opaque bytes, which
 * is what `Platform.http.postBytes` returns to a plugin as Base64.
 */
class LocalGitHubServer {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val responses = mutableMapOf<String, Response>()

    /** Paths (with query string) the server was actually asked for, in order. */
    val requestedPaths = mutableListOf<String>()

    /** Every request the server received, in order, with its method, headers and body. */
    val requests = mutableListOf<RecordedRequest>()

    val port: Int
        get() = server.address.port

    fun start(): LocalGitHubServer {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        return this
    }

    fun stop() {
        server.stop(0)
    }

    /** Answers requests for [path] (query string excluded) with [body] and [status]. */
    fun respond(path: String, body: String, status: Int = 200): LocalGitHubServer {
        responses[path] = Response(status, body.toByteArray(), "application/json", delayMillis = 0)
        return this
    }

    /** Answers with bytes that are not valid UTF-8, which a plugin reads as Base64. */
    fun respondBytes(
        path: String,
        body: ByteArray,
        status: Int = 200,
        contentType: String = "application/octet-stream",
    ): LocalGitHubServer {
        responses[path] = Response(status, body, contentType, delayMillis = 0)
        return this
    }

    /** Answers so slowly that a client with a short read timeout gives up. */
    fun respondSlowly(path: String, body: String, delayMillis: Long): LocalGitHubServer {
        responses[path] = Response(200, body.toByteArray(), "application/json", delayMillis)
        return this
    }

    /**
     * A client that sends everything to this server, whatever host the request was built for.
     *
     * [readTimeoutMillis] is short in the timeout test, and irrelevant everywhere else.
     */
    fun client(readTimeoutMillis: Long = 10_000L): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val local = original.url.newBuilder()
                .scheme("http")
                .host("127.0.0.1")
                .port(port)
                .build()
            chain.proceed(original.newBuilder().url(local).build())
        }
        .build()

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        // The raw query, not the decoded one: this is what the client actually asked for, and the
        // percent-encoding is part of what a plugin's URL building has to get right.
        val rawQuery = exchange.requestURI.rawQuery
        val pathWithQuery = if (rawQuery.isNullOrEmpty()) path else "$path?$rawQuery"
        requestedPaths += pathWithQuery
        requests += RecordedRequest(
            method = exchange.requestMethod,
            path = pathWithQuery,
            headers = exchange.requestHeaders.entries.associate { it.key to it.value.toList() },
            body = exchange.requestBody.readBytes(),
        )

        val response = responses[path]
        try {
            if (response == null) {
                exchange.sendResponseHeaders(404, NOT_FOUND_BODY.length.toLong())
                exchange.responseBody.use { it.write(NOT_FOUND_BODY.toByteArray()) }
                return
            }
            if (response.delayMillis > 0) {
                Thread.sleep(response.delayMillis)
            }
            exchange.responseHeaders.add("Content-Type", response.contentType)
            exchange.sendResponseHeaders(response.status, response.body.size.toLong())
            exchange.responseBody.use { it.write(response.body) }
        } catch (_: Exception) {
            // The client hung up (the timeout test): nothing left to answer.
        }
    }

    /** A request the fixture received; [headers] is keyed the way the JDK normalises header names. */
    data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

        override fun equals(other: Any?): Boolean =
            other is RecordedRequest && method == other.method && path == other.path &&
                headers == other.headers && body.contentEquals(other.body)

        override fun hashCode(): Int =
            ((method.hashCode() * 31 + path.hashCode()) * 31 + headers.hashCode()) * 31 + body.contentHashCode()
    }

    private data class Response(
        val status: Int,
        val body: ByteArray,
        val contentType: String,
        val delayMillis: Long,
    )

    private companion object {
        const val NOT_FOUND_BODY = """{"message":"Not Found"}"""
    }
}
