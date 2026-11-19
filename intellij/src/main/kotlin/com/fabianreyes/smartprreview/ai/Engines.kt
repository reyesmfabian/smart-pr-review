package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle
import com.fabianreyes.smartprreview.http.Http
import com.fabianreyes.smartprreview.http.HttpError
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.intellij.execution.CommandLineUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What a review runs: a command-line [Engine] or a [UrlEngine]. */
sealed interface ReviewEngine {
    val id: String

    /** The model picked for this review; null when a CLI uses its own default. */
    val model: String?
}

/**
 * A CLI engine to run (not the stored setting, `settings.Engine`). [model] is the one picked for this review
 * (already in [args]); without it the CLI uses its own default.
 */
data class Engine(override val id: String, val executable: String, val args: List<String>, override val model: String? = null) : ReviewEngine

/**
 * An engine reached over HTTP with the OpenAI chat completions API (Ollama, LM Studio, vLLM, llama.cpp, OpenRouter…).
 * It cannot read the repository, so it gets the changed files in the prompt, split to fit [maxInputTokens].
 */
data class UrlEngine(
    override val id: String,
    override val model: String,
    val url: String,
    /** Tokens the server takes per request, prompt and answer together. */
    val contextWindow: Int,
    /** Tokens kept for the answer, reasoning included; sent as max_tokens. */
    val maxOutputTokens: Int,
    val apiKey: String?,
) : ReviewEngine {
    val maxInputTokens: Int get() = contextWindow - maxOutputTokens

    /** Without the API key: engines end up in logs. */
    override fun toString() = "UrlEngine(id=$id, model=$model, url=$url)"
}

/** A model of a CLI engine; the id is what the engine's model flag takes. */
data class CliModel(val id: String, val name: String? = null)

private val MODEL_NAME = Regex("""(?!-)[^\s\p{Z}\p{Cc}]{1,200}""")

/** A model name is one argument of the engine: no spaces or control characters, and never an option. */
fun isModelName(value: String): Boolean = MODEL_NAME.matches(value)

private val ANSI_COLOR = Regex("""\p{Cc}\[[0-9;]*[A-Za-z]""")
private val ZERO_WIDTH = Regex("[\u200b-\u200d\ufeff]")
private val MODEL_LINE = Regex("""(\S+)(?:\s+-\s+(.+))?""")
private val SPACES = Regex("""\s+""")

/**
 * Reads the models a CLI prints, one per line as "id" or "id - name" (what `cursor-agent models` prints).
 * Other lines, like its heading, are ignored.
 */
fun parseModelList(output: String): List<CliModel> {
    val models = linkedMapOf<String, CliModel>()
    for (raw in output.lines()) {
        // Without colors, nor the zero-width characters some CLIs pad the names with.
        val line = raw.replace(ANSI_COLOR, "").replace(ZERO_WIDTH, "").trim()
        val match = MODEL_LINE.matchEntire(line) ?: continue
        val id = match.groupValues[1]
        if (!isModelName(id) || id in models) continue
        models[id] = CliModel(id, match.groupValues[2].replace(SPACES, " ").trim().ifEmpty { null })
    }
    return models.values.toList()
}

class EngineException(message: String, val stderr: String = "") : RuntimeException(message)

class EngineCancelled : RuntimeException()

private fun isExecutable(path: Path) = Files.isRegularFile(path) && Files.isExecutable(path)

/**
 * Full path of `command` in PATH (PATHEXT on Windows; extensionless shims only when the name has an extension), or null.
 * Relative PATH entries are ignored: they would resolve against the worktree, which holds the pull request's files.
 */
fun findExecutable(command: String, env: Map<String, String> = EnvironmentUtil.getEnvironmentMap(), windows: Boolean = SystemInfo.isWindows): Path? {
    val given = Paths.get(command)
    if (given.isAbsolute) return given.takeIf(::isExecutable)
    val pathext = (env["PATHEXT"] ?: ".EXE;.CMD;.BAT;.COM").split(";")
    val extensions = when {
        !windows -> listOf("")
        command.substringAfterLast('.', "").isNotEmpty() && '.' in command.substringAfterLast('/').substringAfterLast('\\') -> listOf("") + pathext
        else -> pathext
    }
    // Windows PATH entries may be quoted ("C:\Program Files\x"); an entry that is still invalid is skipped.
    val dirs = (env["PATH"] ?: "").split(File.pathSeparator).mapNotNull {
        try {
            Paths.get(it.trim().removeSurrounding("\"")).takeIf(Path::isAbsolute)
        } catch (_: InvalidPathException) {
            null
        }
    }
    for (dir in dirs) {
        for (extension in extensions) {
            val candidate = dir.resolve(command + extension)
            if (isExecutable(candidate)) return candidate
        }
    }
    return null
}

/** Engines answer a few kilobytes; anything far larger is a runaway process. */
private const val MAX_OUTPUT_MB = 5
private const val MAX_OUTPUT_BYTES = MAX_OUTPUT_MB * 1024 * 1024
private const val MAX_STDERR_BYTES = 64 * 1024

/** SIGTERM to the engine and all its descendants; returns them so a later SIGKILL reaches the same set. */
private fun killTree(process: Process): List<ProcessHandle> {
    val all = process.descendants().toList() + process.toHandle()
    all.forEach { it.destroy() }
    return all
}

/**
 * Runs the engine in `cwd`, using platform quoting (Windows .cmd/.bat shims require a shell).
 * The pull request prompt is sent only on stdin, never included in the command line. Writes the prompt to stdin and closes it, returns stdout. Timeout
 * and cancellation stop the whole process tree (SIGTERM, SIGKILL after 3 s), and the call returns only
 * once the engine has exited, so the worktree is never removed under a running engine.
 */
fun runCli(
    engine: Engine, prompt: String, cwd: Path, indicator: ProgressIndicator?, timeoutMs: Long,
    /** The shell's environment (a GUI-launched IDE has launchd's minimal PATH, and `#!/usr/bin/env node` CLIs need the real one). */
    env: Map<String, String> = EnvironmentUtil.getEnvironmentMap(),
    /** Polled while the engine runs: true stops it like a cancellation (its answer is no longer wanted). */
    stop: () -> Boolean = { false },
): String {
    // IntelliJ handles Windows shell shims, native quoting and metacharacters together.
    val command = CommandLineUtil.toCommandLine(engine.executable, engine.args)
    val builder = ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(false)
    builder.environment().apply { clear(); putAll(env) }
    val process = builder.start()

    val stdout = ByteArrayOutputStream()
    val tooLarge = java.util.concurrent.atomic.AtomicBoolean(false)
    val stderr = ByteArrayOutputStream()
    // Two reader threads, so neither pipe can fill and block the engine.
    val outReader = Thread {
        val buffer = ByteArray(8192)
        try {
            process.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    stdout.write(buffer, 0, n)
                    if (stdout.size() > MAX_OUTPUT_BYTES) {
                        tooLarge.set(true)
                        break
                    }
                }
            }
        } catch (_: IOException) {
        }
    }
    val errReader = Thread {
        val buffer = ByteArray(8192)
        try {
            process.errorStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(stderr) {
                        stderr.write(buffer, 0, n)
                        if (stderr.size() > 2 * MAX_STDERR_BYTES) {
                            val tail = stderr.toByteArray().takeLast(MAX_STDERR_BYTES).toByteArray()
                            stderr.reset()
                            stderr.write(tail)
                        }
                    }
                }
            }
        } catch (_: IOException) {
        }
    }
    // A third thread for stdin: an engine that never reads must not block the timeout. It may also exit early.
    val writer = Thread {
        try {
            process.outputStream.use { it.write(prompt.toByteArray(StandardCharsets.UTF_8)) }
        } catch (_: IOException) {
        }
    }
    listOf(outReader, errReader, writer).forEach { it.isDaemon = true; it.start() }

    var failure: RuntimeException? = null
    var interrupted = false
    // Waits after the kill must run to completion even if this thread is interrupted again; the flag is restored at the end.
    fun <T> uninterruptibly(block: () -> T): T {
        while (true) {
            try {
                return block()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }
    fun tooLargeError() = EngineException(SmartPrReviewBundle.message("ai.tooLarge", MAX_OUTPUT_MB))
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    try {
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            failure = when {
                indicator?.isCanceled == true || stop() -> EngineCancelled()
                tooLarge.get() -> tooLargeError()
                System.nanoTime() >= deadline -> EngineException(SmartPrReviewBundle.message("ai.timeout", timeoutMs / 60000.0))
                else -> null
            }
            if (failure != null) break
        }
    } catch (_: InterruptedException) {
        interrupted = true
        failure = EngineCancelled()
    }
    if (failure != null || process.isAlive) {
        val tree = killTree(process)
        val grace = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        uninterruptibly { process.waitFor(maxOf(0L, grace - System.nanoTime()), TimeUnit.NANOSECONDS) }
        // The engine, or descendants that outlived it (their SIGTERM handler ignored it), still alive after the grace period.
        tree.forEach { if (it.isAlive) it.destroyForcibly() }
        uninterruptibly { process.waitFor() }
    }
    uninterruptibly { outReader.join(5_000) }
    uninterruptibly { errReader.join(5_000) }
    // After the joins: the reader may cross the cap on the bytes still in the pipe when the engine exited.
    if (failure == null && tooLarge.get()) failure = tooLargeError()
    if (interrupted) {
        Thread.currentThread().interrupt()
        throw EngineCancelled()
    }
    failure?.let { throw it }

    val errText = synchronized(stderr) { String(stderr.toByteArray().takeLast(MAX_STDERR_BYTES).toByteArray(), StandardCharsets.UTF_8) }
    val code = process.exitValue()
    if (code != 0) {
        throw EngineException(SmartPrReviewBundle.message("ai.exited", code, errText.trim().takeLast(500)), errText)
    }
    return stdout.toString(StandardCharsets.UTF_8)
}

/** The models `executable` lists with `args`. Runs outside the repository: listing needs none of it. Blocking. */
fun listCliModels(executable: String, args: List<String>, indicator: ProgressIndicator?, timeoutMs: Long): List<CliModel> =
    parseModelList(runCli(Engine("models", executable, args), "", Paths.get(System.getProperty("java.io.tmpdir")), indicator, timeoutMs))

/** The engine's URL without trailing slashes; its API key is stored under it, so it never reaches another server. */
fun apiBase(url: String): String = url.trimEnd('/')

/** The PasswordSafe key of the API key of the URL engine at [url]. */
fun apiKeySecret(url: String): String = "engineApiKey:${apiBase(url)}"

/**
 * Tokens of [text] when the server cannot count them: one per 3 characters, low on purpose. Code averages 3 to 4, and
 * a prompt counted short may be cut by the server without notice (Ollama does).
 */
fun estimateTokens(text: String): Int = (text.length + 2) / 3

private fun HttpRequest.Builder.authorization(apiKey: String?): HttpRequest.Builder =
    if (apiKey.isNullOrEmpty()) this else header("Authorization", "Bearer $apiKey")

/** A failed request, with the server named and what to do about a rejected key. */
private fun apiError(url: String, error: Throwable): Exception = when (error) {
    is HttpError -> EngineException(
        if (error.status == 401 || error.status == 403) SmartPrReviewBundle.message("ai.apiKeyRejected", url, error.status)
        else SmartPrReviewBundle.message("ai.httpFailed", url, error.status, error.message ?: ""),
    )
    // A refused connection or an unknown host; ConnectException often has no message.
    is IOException -> EngineException(SmartPrReviewBundle.message("ai.unreachable", url, error.message ?: error.javaClass.simpleName))
    is JsonParseException -> EngineException(SmartPrReviewBundle.message("ai.notOpenAi", url))
    is Exception -> error
    else -> RuntimeException(error)
}

private fun JsonElement?.obj(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonElement?.string(): String? = this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

/** The models the server lists at GET {url}/models. Blocking: never call on the EDT. */
fun listUrlModels(url: String, apiKey: String?, timeoutMs: Long): List<CliModel> {
    val request = HttpRequest.newBuilder(URI("${apiBase(url)}/models")).timeout(Duration.ofMillis(timeoutMs)).authorization(apiKey).GET().build()
    val body = try {
        Http.requestJson(request)
    } catch (e: Exception) {
        throw apiError(url, e)
    }
    val data = body.obj()?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return data.mapNotNull { model -> model.obj()?.get("id").string()?.takeIf(::isModelName)?.let { CliModel(it) } }
}

/** Waits for [future] while polling [indicator] and [stop]; giving up (timeout, cancellation, interrupt) cancels it. */
private fun <T> await(future: CompletableFuture<T>, indicator: ProgressIndicator?, timeoutMs: Long, stop: () -> Boolean): T {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    try {
        while (true) {
            if (indicator?.isCanceled == true || stop()) throw EngineCancelled()
            if (System.nanoTime() >= deadline) throw EngineException(SmartPrReviewBundle.message("ai.timeout", timeoutMs / 60000.0))
            try {
                return future.get(200, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
            }
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw EngineCancelled()
    } finally {
        // Aborts the request when it was given up; nothing once it completed.
        future.cancel(true)
    }
}

/** Reasoning that servers which do not split it out leave in the answer, where its braces would confuse the parser. */
private val THINKING = Regex("""<think>[\s\S]*?</think>""")

/**
 * Sends [prompt] to [engine] and returns its answer. Timeout and cancellation ([indicator], [stop], an interrupt)
 * abort the request. Blocking: never call on the EDT.
 */
fun runChat(engine: UrlEngine, prompt: String, indicator: ProgressIndicator?, timeoutMs: Long, stop: () -> Boolean = { false }): String {
    val body = JsonObject().apply {
        addProperty("model", engine.model)
        add("messages", JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt) }) })
        addProperty("max_tokens", engine.maxOutputTokens)
        addProperty("stream", false)
    }
    val request = HttpRequest.newBuilder(URI("${apiBase(engine.url)}/chat/completions"))
        .authorization(engine.apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build()
    val json = try {
        Http.parseResponse(await(Http.client.sendAsync(request, HttpResponse.BodyHandlers.ofString()), indicator, timeoutMs, stop))
    } catch (e: ExecutionException) {
        throw apiError(engine.url, e.cause ?: e)
    } catch (e: HttpError) {
        throw apiError(engine.url, e)
    } catch (e: JsonParseException) {
        throw apiError(engine.url, e)
    }
    val choice = json.obj()?.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull().obj()
    // Reasoning models spend max_tokens thinking too: a cut answer has no JSON, say why instead.
    if (choice?.get("finish_reason").string() == "length") throw EngineException(SmartPrReviewBundle.message("ai.outputCut", engine.maxOutputTokens))
    // Something that is not a chat completions API, such as a URL missing its /v1.
    val content = choice?.get("message").obj()?.get("content").string() ?: throw EngineException(SmartPrReviewBundle.message("ai.notOpenAi", engine.url))
    return content.replace(THINKING, "")
}

/** Prompts that fit a window, and the files too large for any. */
data class PromptBatches(val prompts: List<String>, val skipped: List<String>)

/**
 * Splits [files] (halving the list) until each prompt fits [maxTokens], with a 10% margin because token counts are
 * estimates. A single file that does not fit is sent without its contents (diff only); one that still does not fit is skipped.
 */
fun promptsWithinLimit(files: List<String>, maxTokens: Int, makePrompt: (files: List<String>, withContents: Boolean) -> String): PromptBatches {
    fun fits(prompt: String) = estimateTokens(prompt) <= maxTokens * 0.9
    val prompt = makePrompt(files, true)
    if (fits(prompt)) return PromptBatches(listOf(prompt), emptyList())
    if (files.size <= 1) {
        val diffOnly = makePrompt(files, false)
        return if (fits(diffOnly)) PromptBatches(listOf(diffOnly), emptyList()) else PromptBatches(emptyList(), files)
    }
    val half = (files.size + 1) / 2
    val first = promptsWithinLimit(files.take(half), maxTokens, makePrompt)
    val second = promptsWithinLimit(files.drop(half), maxTokens, makePrompt)
    return PromptBatches(first.prompts + second.prompts, first.skipped + second.skipped)
}
