package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.http.HttpCall
import com.fabianreyes.smartprreview.http.HttpError
import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Records every call and answers from `handler`: a JSON text, or an HttpError to throw. */
class FakeTransport(private val handler: (HttpCall) -> Any?) : (HttpCall) -> JsonElement? {
    val calls = mutableListOf<HttpCall>()

    override fun invoke(call: HttpCall): JsonElement? {
        calls += call
        return when (val answer = handler(call)) {
            null -> null
            is HttpError -> throw answer
            is String -> JsonParser.parseString(answer)
            is JsonElement -> answer
            else -> JsonParser.parseString(com.fabianreyes.smartprreview.http.toJson(answer))
        }
    }

    /** Parsed JSON body of the n-th call. */
    fun body(n: Int): JsonElement = JsonParser.parseString(calls[n].body ?: error("call $n has no body"))
    fun urls(): List<String> = calls.map { it.url }
}
