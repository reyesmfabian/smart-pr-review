package com.fabianreyes.smartprreview.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPrReviewSettingsTest {
    @Test
    fun `defaults match the VS Code extension`() {
        val app = SmartPrReviewSettings.State()
        assertEquals(emptyList<String>(), app.azureDevOpsHosts)
        assertFalse(app.allowApproval)
        assertEquals("copilot", app.defaultEngine)
        assertEquals("{}", app.enginesJson)
        assertEquals(10, app.aiTimeoutMinutes)
        assertFalse(app.aiLabelComments)
        val project = SmartPrReviewProjectSettings.State()
        assertEquals(".smart-review/profiles", project.profilesPath)
        assertEquals("", project.aiLanguage)
    }

    @Test
    fun `bundled engines come first and the user can add or override`() {
        val settings = SmartPrReviewSettings()
        settings.loadState(SmartPrReviewSettings.State().apply {
            enginesJson = """{"mine": {"command": "my-agent", "args": ["--ask"]}, "claude": {"command": "claude-wrapper"}}"""
        })
        val engines = settings.engines
        assertEquals(listOf("copilot", "claude", "codex", "cursor", "mine"), engines.keys.toList())
        assertEquals(Engine("my-agent", listOf("--ask")), engines["mine"])
        assertEquals(Engine("claude-wrapper", emptyList()), engines["claude"])
        assertEquals("copilot", engines.getValue("copilot").command)
        assertTrue("--no-custom-instructions" in engines.getValue("copilot").args)
        assertEquals("cursor-agent", engines.getValue("cursor").command)
    }

    @Test
    fun `engines without a command or with broken json are rejected`() {
        assertTrue(SmartPrReviewSettings.parseEngines("""{"x": {"args": []}}""").isFailure)
        assertTrue(SmartPrReviewSettings.parseEngines("not json").isFailure)
        assertTrue(SmartPrReviewSettings.parseEngines("""["list"]""").isFailure)
        assertEquals(emptyMap<String, Engine>(), SmartPrReviewSettings.parseEngines("").getOrThrow())
        // Broken user JSON never hides the bundled engines.
        val settings = SmartPrReviewSettings().apply { loadState(SmartPrReviewSettings.State().apply { enginesJson = "oops" }) }
        assertEquals(SmartPrReviewSettings.BUNDLED_ENGINES, settings.engines)
    }
}
