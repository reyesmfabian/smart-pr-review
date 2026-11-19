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
    fun `bundled engines ask for the model instead of inheriting or pinning one`() {
        val engines = SmartPrReviewSettings.BUNDLED_ENGINES
        assertEquals("--model", engines.getValue("claude").modelArg)
        assertEquals("--model", engines.getValue("cursor").modelArg)
        assertEquals("-m", engines.getValue("codex").modelArg)
        // The picker appends the flag: a pinned one in args would be given twice.
        for ((id, engine) in engines) assertFalse("$id pins a model in args", engine.modelArg != null && engine.modelArg in engine.args)
        assertEquals(listOf("sonnet", "opus", "haiku", "fable"), engines.getValue("claude").models)
        // Cursor reviewed with "auto" before the picker: it stays the first offer.
        assertEquals("auto", engines.getValue("cursor").defaultModel)
        assertEquals(listOf("models"), engines.getValue("cursor").listModelsArgs)
        // Codex would otherwise inherit the reasoning effort of the user's own config (often the highest).
        assertTrue(engines.getValue("codex").args.joinToString(" ").contains("-c model_reasoning_effort=\"medium\""))
        // codex reads the prompt from stdin because of the "-": it stays the last argument.
        assertEquals("-", engines.getValue("codex").args.last())
    }

    @Test
    fun `the user's engines can declare how they take a model`() {
        val json = """{"mine": {"command": "my-agent", "args": ["--ask"], "modelArg": "--model", "models": ["small", "large"], "listModelsArgs": ["models"], "defaultModel": "small"}}"""
        assertEquals(
            mapOf("mine" to Engine("my-agent", listOf("--ask"), "--model", listOf("small", "large"), listOf("models"), "small")),
            SmartPrReviewSettings.parseEngines(json).getOrThrow(),
        )
        assertEquals(Engine("x"), SmartPrReviewSettings.parseEngines("""{"x": {"command": "x", "modelArg": " "}}""").getOrThrow()["x"])
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
