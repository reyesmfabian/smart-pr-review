package com.fabianreyes.smartprreview.http

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/** Tiny Gson helpers so the providers read like the TypeScript they mirror. Missing or null → null. */
fun JsonElement?.obj(name: String): JsonObject? = (this as? JsonObject)?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
fun JsonElement?.arr(name: String): List<JsonElement> = (this as? JsonObject)?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()
fun JsonElement?.str(name: String): String? = (this as? JsonObject)?.get(name)?.takeIf { it.isJsonPrimitive }?.asString
fun JsonElement?.int(name: String): Int? = (this as? JsonObject)?.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
fun JsonElement?.bool(name: String): Boolean? = (this as? JsonObject)?.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
val JsonElement?.isNullish: Boolean get() = this == null || this is JsonNull

private val GSON = GsonBuilder().disableHtmlEscaping().create()

/** JSON text of a Kotlin map/list/primitive tree (Gson serializes LinkedHashMap in insertion order). */
fun toJson(value: Any?): String = GSON.toJson(value)
