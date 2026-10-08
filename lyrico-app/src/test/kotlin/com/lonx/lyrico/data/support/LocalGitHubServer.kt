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
 */
class LocalGitHubServer {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val responses = mutableMapOf<String, Response>()

    /** Paths (with query string) the server was actually asked for, in order. */
    val requestedPaths = mutableListOf<String>()

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
        responses[path] = Response(status, body, delayMillis = 0)
        return this
    }

    /** Answers so slowly that a client with a short read timeout gives up. */
    fun respondSlowly(path: String, body: String, delayMillis: Long): LocalGitHubServer {
        responses[path] = Response(200, body, delayMillis)
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
        requestedPaths += if (exchange.requestURI.query.isNullOrEmpty()) {
            path
        } else {
            "$path?${exchange.requestURI.query}"
        }

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
            val bytes = response.body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (_: Exception) {
            // The client hung up (the timeout test): nothing left to answer.
        }
    }

    private data class Response(
        val status: Int,
        val body: String,
        val delayMillis: Long
    )

    private companion object {
        const val NOT_FOUND_BODY = """{"message":"Not Found"}"""
    }
}
