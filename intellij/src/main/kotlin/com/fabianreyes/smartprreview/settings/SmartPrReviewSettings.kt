package com.fabianreyes.smartprreview.settings

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** A command-line AI engine: `command` gets the prompt on stdin and answers on stdout. */
data class Engine(val command: String, val args: List<String> = emptyList())

/** Machine-wide settings: the same fields and defaults as the VS Code `smartPrReview.*` settings. */
@Service
@State(name = "SmartPrReviewSettings", storages = [Storage("smartPrReview.xml", roamingType = RoamingType.DISABLED)])
class SmartPrReviewSettings : PersistentStateComponent<SmartPrReviewSettings.State> {
    class State {
        var azureDevOpsHosts: MutableList<String> = mutableListOf()
        var allowApproval: Boolean = false
        var defaultEngine: String = "copilot"
        /** JSON object of the engines the user adds, merged over [BUNDLED_ENGINES]. */
        var enginesJson: String = "{}"
        var aiTimeoutMinutes: Int = 10
        var aiLabelComments: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    val azureDevOpsHosts: List<String> get() = state.azureDevOpsHosts
    val allowApproval: Boolean get() = state.allowApproval
    val defaultEngine: String get() = state.defaultEngine
    val aiTimeoutMinutes: Int get() = state.aiTimeoutMinutes
    val aiLabelComments: Boolean get() = state.aiLabelComments

    /** Bundled engines plus the user's; a user entry with a bundled key replaces it. Broken JSON keeps the bundled ones. */
    val engines: Map<String, Engine>
        get() = BUNDLED_ENGINES + parseEngines(state.enginesJson).getOrDefault(emptyMap())

    companion object {
        fun getInstance(): SmartPrReviewSettings = service()

        val BUNDLED_ENGINES: Map<String, Engine> = linkedMapOf(
            // GitHub Copilot CLI: prompt piped on stdin, only the answer on stdout, file reads only (no shell, writes, web or memory).
            "copilot" to Engine("copilot", listOf(
                "--silent", "--stream=off", "--no-color", "--no-custom-instructions", "--no-auto-update",
                "--available-tools=view,glob,grep", "--allow-tool=read",
                "--deny-tool=shell", "--deny-tool=write", "--deny-tool=url", "--deny-tool=memory",
            )),
            "claude" to Engine("claude", listOf(
                "-p", "--restricted", "--strict-mcp-config", "--tools", "Read", "Grep", "Glob",
                "--permission-mode", "dontAsk", "--no-session-persistence", "--output-format", "text",
            )),
            "codex" to Engine("codex", listOf(
                "exec", "--sandbox", "read-only", "-c", "approval_policy=\"never\"", "--skip-git-repo-check",
                "--ephemeral", "--color", "never", "-",
            )),
            "cursor" to Engine("cursor-agent", listOf(
                "-p", "--mode", "ask", "--sandbox", "enabled", "--trust", "--model", "auto", "--output-format", "text",
            )),
        )

        private class EngineJson(val command: String? = null, val args: List<String>? = null)

        /** Parses the user's engines JSON ({"key": {"command": "...", "args": [...]}}); the failure names the problem. */
        fun parseEngines(json: String): Result<Map<String, Engine>> = runCatching {
            val type = object : TypeToken<Map<String, EngineJson?>>() {}.type
            val parsed: Map<String, EngineJson?> = Gson().fromJson(json.ifBlank { "{}" }, type) ?: emptyMap()
            parsed.mapValues { (key, engine) ->
                val command = engine?.command?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("engine \"$key\" needs a command")
                Engine(command, engine.args ?: emptyList())
            }
        }
    }
}
