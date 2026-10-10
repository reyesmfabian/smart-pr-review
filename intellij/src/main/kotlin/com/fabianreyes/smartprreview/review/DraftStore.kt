package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.http.int
import com.fabianreyes.smartprreview.http.str
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import java.util.UUID

/** String storage behind the drafts (the equivalent of VS Code's workspace Memento). A null value removes the key. */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class PropertiesKeyValueStore(project: Project) : KeyValueStore {
    private val properties = PropertiesComponent.getInstance(project)
    override fun get(key: String): String? = properties.getValue(key)
    override fun set(key: String, value: String?) = properties.setValue(key, value)
}

/** Draft comments and the review summary of one pull request, persisted as JSON. */
class DraftStore(private val store: KeyValueStore, /** e.g. "github:octo/app:7" */ private val key: String) {
    @Synchronized
    fun all(): List<Draft> {
        val json = store.get("drafts:$key") ?: return emptyList()
        return try {
            JsonParser.parseString(json).asJsonArray.map { fromJson(it.asJsonObject) }
        } catch (e: RuntimeException) {
            emptyList() // corrupt entry: start over rather than break the review
        }
    }

    @Synchronized
    fun add(draft: Draft): Draft {
        val created = draft.copy(id = UUID.randomUUID().toString())
        save(all() + created)
        return created
    }

    @Synchronized
    fun update(id: String, body: String) = save(all().map { if (it.id == id) it.copy(body = body) else it })

    @Synchronized
    fun remove(ids: Collection<String>) {
        val removed = ids.toSet()
        save(all().filter { it.id !in removed })
    }

    fun summary(): String = store.get("summary:$key") ?: ""

    fun setSummary(text: String) = store.set("summary:$key", text)

    private fun save(drafts: List<Draft>) {
        store.set("drafts:$key", JsonArray().apply { drafts.forEach { add(toJson(it)) } }.toString())
    }

    private fun toJson(d: Draft) = JsonObject().apply {
        addProperty("id", d.id)
        addProperty("path", d.path)
        addProperty("startLine", d.startLine)
        addProperty("endLine", d.endLine)
        d.startColumn?.let { addProperty("startColumn", it) }
        d.endColumn?.let { addProperty("endColumn", it) }
        addProperty("body", d.body)
        addProperty("headSha", d.headSha)
        add("origin", JsonObject().apply {
            when (val o = d.origin) {
                DraftOrigin.Manual -> addProperty("type", "manual")
                is DraftOrigin.Ai -> {
                    addProperty("type", "ai")
                    addProperty("profile", o.profile)
                    addProperty("engine", o.engine)
                    addProperty("severity", o.severity.name.lowercase())
                }
            }
        })
    }

    private fun fromJson(j: JsonObject): Draft {
        val o = j.getAsJsonObject("origin")
        val origin = if (o.str("type") == "ai") {
            DraftOrigin.Ai(o.str("profile")!!, o.str("engine")!!, DraftOrigin.Severity.valueOf(o.str("severity")!!.uppercase()))
        } else {
            DraftOrigin.Manual
        }
        return Draft(
            j.str("id")!!, j.str("path")!!, j.int("startLine")!!, j.int("endLine")!!, j.int("startColumn"), j.int("endColumn"),
            j.str("body")!!, j.str("headSha")!!, origin,
        )
    }
}
