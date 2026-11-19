package com.fabianreyes.smartprreview.settings

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * A command-line AI engine: `command` gets the prompt on stdin and answers on stdout. With [modelArg] (the flag that
 * selects the model, e.g. "--model") the review asks for a model and appends the flag and the model to [args]:
 * [models] are offered, plus the ones the command prints with [listModelsArgs] (one per line, "id" or "id - name"),
 * and [defaultModel] comes first until another one is picked.
 *
 * With [url] it is a server with the OpenAI chat completions API instead (Ollama, LM Studio, vLLM, llama.cpp,
 * OpenRouter…) and `command` is empty: the review always asks for a model, of [models] plus the ones the server
 * lists. [contextWindow] is the tokens the server takes per request, prompt and answer together; [maxOutputTokens]
 * of them are kept for the answer (reasoning included) and sent as max_tokens.
 */
data class Engine(
    val command: String,
    val args: List<String> = emptyList(),
    val modelArg: String? = null,
    val models: List<String> = emptyList(),
    val listModelsArgs: List<String> = emptyList(),
    val defaultModel: String? = null,
    val url: String? = null,
    val contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
)

const val DEFAULT_CONTEXT_WINDOW = 8192
const val DEFAULT_MAX_OUTPUT_TOKENS = 4096

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
            ), modelArg = "--model", models = listOf("sonnet", "opus", "haiku", "fable")),
            // Medium reasoning effort: codex would otherwise inherit the one of the user's own config (often the highest).
            "codex" to Engine("codex", listOf(
                "exec", "--sandbox", "read-only", "-c", "approval_policy=\"never\"", "-c", "model_reasoning_effort=\"medium\"",
                "--skip-git-repo-check", "--ephemeral", "--color", "never", "-",
            ), modelArg = "-m"),
            // No model in the arguments: the picker appends it, and "auto" (what reviews used before) is offered first.
            "cursor" to Engine("cursor-agent", listOf(
                "-p", "--mode", "ask", "--sandbox", "enabled", "--trust", "--output-format", "text",
            ), modelArg = "--model", listModelsArgs = listOf("models"), defaultModel = "auto"),
        )

        private class EngineJson(
            val command: String? = null,
            val args: List<String>? = null,
            val modelArg: String? = null,
            val models: List<String>? = null,
            val listModelsArgs: List<String>? = null,
            val defaultModel: String? = null,
            val url: String? = null,
            val contextWindow: Int? = null,
            val maxOutputTokens: Int? = null,
        )

        private fun isHttpUrl(value: String) = try {
            java.net.URI(value).let { (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrEmpty() }
        } catch (_: java.net.URISyntaxException) {
            false
        }

        /** An engine reached by URL; the failure says what to fix. */
        private fun urlEngine(key: String, engine: EngineJson, url: String): Engine {
            require(isHttpUrl(url)) { "engine \"$key\": url must start with http:// or https://" }
            val window = engine.contextWindow ?: DEFAULT_CONTEXT_WINDOW
            val output = engine.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS
            require(window > 0 && output > 0) { "engine \"$key\": contextWindow and maxOutputTokens must be positive" }
            require(output < window) { "engine \"$key\": maxOutputTokens ($output) leaves no room for the prompt in contextWindow ($window)" }
            return Engine(
                "", models = engine.models ?: emptyList(), defaultModel = engine.defaultModel?.takeIf { it.isNotBlank() },
                url = url, contextWindow = window, maxOutputTokens = output,
            )
        }

        /**
         * Parses the user's engines JSON ({"key": {"command": "...", "args": [...], "modelArg": "--model", ...}}, or
         * {"key": {"url": "http://...", "contextWindow": 32768, ...}}); the failure names the problem.
         */
        fun parseEngines(json: String): Result<Map<String, Engine>> = runCatching {
            val type = object : TypeToken<Map<String, EngineJson?>>() {}.type
            val parsed: Map<String, EngineJson?> = Gson().fromJson(json.ifBlank { "{}" }, type) ?: emptyMap()
            parsed.mapValues { (key, engine) ->
                val url = engine?.url?.takeIf { it.isNotBlank() }
                if (engine != null && url != null) return@mapValues urlEngine(key, engine, url)
                val command = engine?.command?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("engine \"$key\" needs a command or a url")
                Engine(
                    command, engine.args ?: emptyList(), engine.modelArg?.takeIf { it.isNotBlank() }, engine.models ?: emptyList(),
                    engine.listModelsArgs ?: emptyList(), engine.defaultModel?.takeIf { it.isNotBlank() },
                )
            }
        }
    }
}
