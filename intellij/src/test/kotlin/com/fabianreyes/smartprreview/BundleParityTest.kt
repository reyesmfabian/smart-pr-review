package com.fabianreyes.smartprreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.readText

/** English and Spanish bundles carry the same keys, and the code only uses keys that exist. */
class BundleParityTest {
    private fun keys(name: String): Set<String> {
        val stream = javaClass.getResourceAsStream("/messages/$name") ?: error("missing /messages/$name")
        return stream.use { Properties().apply { load(it.reader(Charsets.UTF_8)) } }.stringPropertyNames()
    }

    @Test
    fun `spanish bundle has exactly the english keys`() {
        val en = keys("SmartPrReviewBundle.properties")
        assertTrue("english bundle is empty", en.isNotEmpty())
        assertEquals(en, keys("SmartPrReviewBundle_es.properties"))
    }

    @Test
    fun `every key used by the code and plugin xml exists`() {
        val en = keys("SmartPrReviewBundle.properties")
        val sources = Files.walk(Path.of("src/main")).use { paths ->
            paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".kt") || it.toString().endsWith(".xml")) }.toList()
        }
        val used = sources.flatMap { file -> USED.findAll(file.readText()).map { it.groupValues[1].ifEmpty { it.groupValues[2] } } }.toSet()
        assertEquals(emptySet<String>(), used - en)
    }

    companion object {
        // message("key") in Kotlin, key="key" in plugin.xml
        private val USED = Regex("""message\(\s*"([^"]+)"|\bkey="([^"]+)"""")
    }
}
