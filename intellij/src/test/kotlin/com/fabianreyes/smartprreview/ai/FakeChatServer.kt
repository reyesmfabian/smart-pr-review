package com.fabianreyes.smartprreview.ai

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** A local OpenAI-compatible server that records each request and answers with [answer] (status, JSON body). */
class FakeChatServer(private val answer: () -> Pair<Int, String>) : AutoCloseable {
    data class Request(val method: String, val path: String, val authorization: String?, val body: String)

    val requests = CopyOnWriteArrayList<Request>()
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requests += Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), body)
            val (status, text) = answer()
            val bytes = text.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        // A thread per request, as a real server takes them: the default executor would queue them itself.
        executor = pool
        start()
    }

    /** With a trailing slash, as users may write it. */
    val url get() = "http://127.0.0.1:${server.address.port}/v1/"

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}

/** A chat completion answering [content]. */
fun completion(content: String, finishReason: String = "stop") =
    200 to """{"choices":[{"message":{"content":${Gson().toJson(content)}},"finish_reason":"$finishReason"}]}"""
