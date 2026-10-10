package com.fabianreyes.smartprreview.http

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class HttpError(val status: Int, message: String, val headers: Map<String, String> = emptyMap()) : RuntimeException(message) {
    /** Header lookup, case-insensitive like HTTP. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** One HTTP call as the providers describe it; the transport turns it into a request. */
data class HttpCall(val method: String, val url: String, val headers: Map<String, String>, val body: String? = null) {
    /** Headers carry the Authorization token: never print them. */
    override fun toString() = "$method $url"
}

/** How a provider reaches the network; tests substitute a fake. */
typealias Transport = (HttpCall) -> JsonElement?

private val LOG = logger<Http>()

/** The plugin's HTTP client. Callers add their own headers (Authorization among them) to the request. */
object Http {
    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    /** Sends a call with a 60 s timeout. Blocking: never call on the EDT. */
    fun send(call: HttpCall): JsonElement? = requestJson(request(call))

    internal fun request(call: HttpCall): HttpRequest {
        val builder = HttpRequest.newBuilder(URI(call.url)).timeout(Duration.ofSeconds(60))
        call.headers.forEach { (name, value) -> builder.header(name, value) }
        val publisher = call.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        return builder.method(call.method, publisher).build()
    }

    /**
     * Sends `request` and parses the JSON body; null for an empty body (e.g. 204). Throws HttpError on
     * non-2xx. Never logs headers (they carry tokens). Blocking: never call on the EDT.
     */
    fun requestJson(request: HttpRequest): JsonElement? {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        LOG.debug { "${request.method()} ${request.uri()} → ${response.statusCode()}" }
        val headers = response.headers().map().mapValues { it.value.joinToString(", ") }
        // Azure DevOps answers rejected credentials with 203 and an HTML sign-in page; a proxy or SSO in front
        // of a server may answer 200 with one. The APIs we call only return JSON on success.
        val ok = response.statusCode() in 200..299
        val html = ok && response.headers().firstValue("content-type").orElse("").contains("text/html")
        if (response.statusCode() == 203 || html) throw HttpError(401, "Not authorized", headers)
        val text = response.body()
        if (!ok) throw HttpError(response.statusCode(), errorMessage(text).ifEmpty { "HTTP ${response.statusCode()}" }, headers)
        return if (text.isBlank()) null else JsonParser.parseString(text)
    }
}

/** `message` plus `errors[].message` (GitHub) joined with " — "; a non-JSON body is quoted up to 200 chars. */
internal fun errorMessage(text: String): String {
    val body = try {
        JsonParser.parseString(text)
    } catch (e: RuntimeException) {
        null
    }
    if (body == null || !body.isJsonObject) return text.take(200)
    val obj = body.asJsonObject
    fun string(element: JsonElement?): String? = element?.takeIf { it.isJsonPrimitive }?.asString
    val details = obj.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.map { e -> if (e.isJsonObject) string(e.asJsonObject.get("message")) else string(e) }
        ?: emptyList()
    return (listOf(string(obj.get("message"))) + details).filterNotNull().filter { it.isNotEmpty() }.joinToString(" — ")
}

/** The user-facing text of an error: translated for HttpError, the message itself otherwise. */
fun describeError(error: Throwable): String {
    if (error !is HttpError) return error.message ?: error.toString()
    val reset = error.header("x-ratelimit-reset")?.toLongOrNull()
    if ((error.status == 403 || error.status == 429) && error.header("x-ratelimit-remaining") == "0" && reset != null) {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochSecond(reset).atZone(ZoneId.systemDefault()))
        return message("http.rateLimit", time)
    }
    val detail = error.message ?: ""
    return when (error.status) {
        401 -> message("http.unauthorized")
        403 -> message("http.forbidden", detail)
        404 -> message("http.notFound", detail)
        422 -> message("http.rejected", detail)
        else -> message("http.failed", error.status, detail)
    }
}
