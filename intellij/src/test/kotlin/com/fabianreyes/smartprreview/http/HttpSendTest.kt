package com.fabianreyes.smartprreview.http

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Optional

class HttpSendTest {
    private lateinit var server: HttpServer
    private lateinit var base: String

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Echoes method, two headers and the body back as JSON.
        server.createContext("/echo") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val json = toJson(mapOf(
                "method" to ex.requestMethod,
                "authorization" to ex.requestHeaders.getFirst("Authorization"),
                "contentType" to ex.requestHeaders.getFirst("Content-Type"),
                "body" to body,
            )).toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/forbidden") { ex -> ex.sendResponseHeaders(403, -1); ex.close() }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun `sends method, headers and body`() {
        val echo = Http.send(HttpCall("POST", "$base/echo", mapOf("Authorization" to "Bearer t", "Content-Type" to "application/json"), """{"a":1}"""))
        assertEquals("POST", echo.str("method"))
        assertEquals("Bearer t", echo.str("authorization"))
        assertEquals("application/json", echo.str("contentType"))
        assertEquals("""{"a":1}""", echo.str("body"))
        val get = Http.send(HttpCall("GET", "$base/echo", emptyMap()))
        assertEquals("GET", get.str("method"))
        assertEquals("", get.str("body"))
    }

    @Test
    fun `http errors propagate`() {
        assertEquals(403, assertThrows(HttpError::class.java) { Http.send(HttpCall("GET", "$base/forbidden", emptyMap())) }.status)
    }

    @Test
    fun `toString hides headers`() {
        val call = HttpCall("GET", "https://x/y", mapOf("Authorization" to "Bearer SECRET"))
        assertFalse(call.toString().contains("SECRET"))
        assertEquals("GET https://x/y", call.toString())
    }

    @Test
    fun `requests time out after 60 seconds`() {
        assertEquals(Optional.of(Duration.ofSeconds(60)), Http.request(HttpCall("GET", "https://x/y", emptyMap())).timeout())
    }
}
