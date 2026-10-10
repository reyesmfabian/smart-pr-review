package com.fabianreyes.smartprreview.auth

/** In-memory secrets for tests. */
class MemorySecrets : Secrets {
    val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
