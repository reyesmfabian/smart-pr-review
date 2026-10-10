package com.fabianreyes.smartprreview.http

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest

class RequestJsonTest {
    private lateinit var server: HttpServer
    private lateinit var base: String

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() = server.stop(0)

    private fun serve(path: String, status: Int, body: String, contentType: String = "application/json") {
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Content-Type", contentType)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun get(path: String) = Http.requestJson(HttpRequest.newBuilder(URI("$base$path")).GET().build())

    @Test
    fun `parses json and returns null for an empty body`() {
        serve("/ok", 200, """{"number": 7}""")
        serve("/empty", 204, "")
        assertEquals(7, get("/ok")!!.asJsonObject["number"].asInt)
        assertNull(get("/empty"))
    }

    @Test
    fun `203 and html answers are a 401`() { // Review Focus 3
        serve("/html", 200, "<html>Sign in</html>", "text/html; charset=utf-8")
        serve("/203", 203, """{"looks": "fine"}""")
        for (path in listOf("/html", "/203")) {
            val error = assertThrows(HttpError::class.java) { get(path) }
            assertEquals(path, 401, error.status)
            assertEquals("Not authorized", error.message)
        }
    }

    @Test
    fun `error bodies become messages and headers are kept`() {
        serve("/gh", 422, """{"message":"Validation Failed","errors":[{"message":"Body can't be blank"},"too long"]}""")
        serve("/text", 500, "<oops>", "text/plain")
        serve("/blank", 503, "")
        assertEquals("Validation Failed — Body can't be blank — too long", assertThrows(HttpError::class.java) { get("/gh") }.message)
        val plain = assertThrows(HttpError::class.java) { get("/text") }
        assertEquals(500, plain.status)
        assertEquals("<oops>", plain.message)
        assertEquals("text/plain", plain.header("content-type"))
        assertEquals("HTTP 503", assertThrows(HttpError::class.java) { get("/blank") }.message)
    }
}
