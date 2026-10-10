package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStore : KeyValueStore {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class DraftStoreTest {
    private val memory = MemoryStore()
    private val store = DraftStore(memory, "github:octo/app:7")

    private fun draft(body: String, origin: DraftOrigin = DraftOrigin.Manual) = Draft("", "src/a.kt", 3, 5, null, null, body, "h".repeat(40), origin)

    @Test
    fun `add assigns distinct ids and persists`() {
        val a = store.add(draft("one"))
        val b = store.add(draft("two"))
        assertTrue(a.id.isNotEmpty())
        assertNotEquals(a.id, b.id)
        assertEquals(listOf(a, b), DraftStore(memory, "github:octo/app:7").all())
    }

    @Test
    fun `round-trips manual and ai origins and columns`() {
        val ai = DraftOrigin.Ai("security", "claude", DraftOrigin.Severity.WARNING)
        val a = store.add(draft("one").copy(startColumn = 2, endColumn = 9))
        val b = store.add(draft("two", ai))
        val json = memory.map.getValue("drafts:github:octo/app:7")
        assertTrue(json, json.contains("\"origin\":{\"type\":\"manual\"}"))
        assertTrue(json, json.contains("\"origin\":{\"type\":\"ai\",\"profile\":\"security\",\"engine\":\"claude\",\"severity\":\"warning\"}"))
        assertEquals(listOf(a, b), store.all())
    }

    @Test
    fun `update changes the body and remove drops ids`() {
        val a = store.add(draft("one"))
        val b = store.add(draft("two"))
        store.update(a.id, "uno")
        assertEquals(listOf("uno", "two"), store.all().map { it.body })
        store.remove(listOf(a.id))
        assertEquals(listOf(b.id), store.all().map { it.id })
    }

    @Test
    fun `summary is kept per key`() {
        assertEquals("", store.summary())
        store.setSummary("LGTM")
        assertEquals("LGTM", store.summary())
        assertEquals("", DraftStore(memory, "github:octo/app:8").summary())
    }
}
