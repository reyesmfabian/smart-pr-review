package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle
import com.google.gson.JsonParser
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class EnginesTest : BasePlatformTestCase() {
    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("smart-pr-review-engines")
    }

    override fun tearDown() {
        try {
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun posixOnly(): Boolean = SystemInfo.isWindows

    private fun sh(script: String) = Engine("sh", "sh", listOf("-c", script))

    private fun file(name: String, executable: Boolean): Path {
        val path = Files.createFile(dir.resolve(name))
        if (executable) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        return path
    }

    // ---- findExecutable ----

    fun testFindsExecutablesInPathAndIgnoresNonExecutables() {
        if (posixOnly()) return
        val tool = file("tool", true)
        file("plain", false)
        Files.createDirectory(dir.resolve("folder"))
        val env = mapOf("PATH" to "/nonexistent${java.io.File.pathSeparator}$dir")
        assertEquals(tool, findExecutable("tool", env, windows = false))
        assertNull(findExecutable("plain", env, windows = false))
        assertNull(findExecutable("folder", env, windows = false))
        assertNull(findExecutable("missing", env, windows = false))
        assertEquals(tool, findExecutable(tool.toString(), emptyMap(), windows = false))
        assertNull(findExecutable(dir.resolve("plain").toString(), emptyMap(), windows = false))
    }

    fun testQuotedPathEntriesAreUnquotedAndInvalidOnesSkipped() {
        if (posixOnly()) return
        val tool = file("tool.exe", true)
        val sep = java.io.File.pathSeparator
        assertEquals(tool, findExecutable("tool", mapOf("PATH" to "\"$dir\"", "PATHEXT" to ".exe"), windows = true))
        assertEquals(tool, findExecutable("tool", mapOf("PATH" to "bad\u0000dir${sep}\"relative\"$sep$dir", "PATHEXT" to ".exe"), windows = true))
    }

    fun testRelativePathEntriesAreIgnoredBecauseTheyResolveAgainstTheWorktree() {
        if (posixOnly()) return
        file("tool", true)
        // A relative path that does reach the file from the process directory.
        val relative = Path.of("").toAbsolutePath().relativize(dir).toString()
        assertNull(findExecutable("tool", mapOf("PATH" to relative), windows = false))
        assertEquals(dir.resolve("tool"), findExecutable("tool", mapOf("PATH" to "$relative${java.io.File.pathSeparator}$dir"), windows = false))
    }

    fun testTheEngineRunsWithTheGivenEnvironmentOnly() {
        if (posixOnly()) return
        val env = mapOf("PATH" to "/usr/bin:/bin", "SMART_PR_REVIEW_TEST_VAR" to "from-the-shell")
        val out = runCli(sh("printf '%s|%s' \"\$SMART_PR_REVIEW_TEST_VAR\" \"\${HOME-unset}\""), "", dir, null, 10_000, env)
        assertEquals("from-the-shell|unset", out)
    }

    fun testWindowsTriesPathextAndPrefersItOverExtensionlessShims() {
        if (posixOnly()) return
        for (name in listOf("codex.cmd", "claude", "claude.CMD")) file(name, true)
        val env = mapOf("PATH" to dir.toString(), "PATHEXT" to ".CMD")
        assertEquals(dir.resolve("codex.cmd"), findExecutable("codex.cmd", env, windows = true))
        assertTrue(findExecutable("claude", env, windows = true).toString().endsWith("claude.CMD", ignoreCase = true))
    }

    fun testWindowsIgnoresExtensionlessShimWithoutExtensionMatch() {
        if (posixOnly()) return
        file("claude", true)
        assertNull(findExecutable("claude", mapOf("PATH" to dir.toString(), "PATHEXT" to ".CMD"), windows = true))
    }

    // ---- runCli ----

    fun testReturnsStdoutWithPromptOnStdinInTheGivenFolder() {
        if (posixOnly()) return
        val out = runCli(sh("cat; pwd"), "hello prompt\n", dir.toRealPath(), null, 10_000)
        assertEquals("hello prompt\n${dir.toRealPath()}\n", out)
    }

    fun testFailingEngineReportsExitCodeAndStderr() {
        if (posixOnly()) return
        val e = failureOf { runCli(sh("echo boom >&2; exit 3"), "", dir, null, 10_000) }
        assertEquals(SmartPrReviewBundle.message("ai.exited", 3, "boom"), e.message)
        assertEquals("boom\n", e.stderr)
    }

    fun testStderrKeepsOnlyTheLast64Kb() {
        if (posixOnly()) return
        val e = failureOf {
            runCli(sh("head -c 100000 /dev/zero | tr '\\0' a >&2; printf END >&2; exit 1"), "", dir, null, 10_000)
        }
        assertEquals(65536, e.stderr.length)
        assertTrue(e.stderr.endsWith("aEND"))
    }

    fun testTimesOutAndStopsChildAndGrandchild() {
        if (posixOnly()) return
        val engine = sh("sleep 60 & echo \$! > grandchild.pid; wait")
        val started = System.currentTimeMillis()
        val e = failureOf { runCli(engine, "", dir, null, 1_000) }
        assertEquals(SmartPrReviewBundle.message("ai.timeout", 1.0 / 60), e.message)
        assertTrue(System.currentTimeMillis() - started < 6_000)
        assertDead(pidOf("grandchild.pid"))
    }

    fun testCancellationStopsTheWholeTree() {
        if (posixOnly()) return
        val engine = sh("sleep 60 & echo \$! > grandchild.pid; wait")
        val indicator = EmptyProgressIndicator()
        var failure: Throwable? = null
        val runner = thread { try { runCli(engine, "", dir, indicator, 30_000) } catch (t: Throwable) { failure = t } }
        val pidFile = dir.resolve("grandchild.pid")
        val deadline = System.currentTimeMillis() + 5_000
        while ((!Files.exists(pidFile) || Files.size(pidFile) == 0L) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val pid = pidOf("grandchild.pid")
        indicator.cancel()
        runner.join(8_000)
        assertFalse(runner.isAlive)
        assertTrue(failure is EngineCancelled)
        assertDead(pid)
    }

    fun testKillsAnEngineThatIgnoresSigterm() {
        if (posixOnly()) return
        val started = System.currentTimeMillis()
        failureOf { runCli(sh("trap '' TERM; sleep 60 & wait; sleep 60"), "", dir, null, 500) }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("took $elapsed ms", elapsed in 3_000..9_000)
    }

    fun testStopsAnUnreasonablyLargeAnswer() {
        if (posixOnly()) return
        val e = failureOf { runCli(sh("head -c 6000000 /dev/zero; sleep 60"), "", dir, null, 30_000) }
        assertEquals(SmartPrReviewBundle.message("ai.tooLarge", 5), e.message)
    }

    fun testAnInterruptedRunnerStillStopsTheTreeBeforeReturning() {
        if (posixOnly()) return
        val engine = sh("trap '' TERM; sleep 60 & echo \$! > grandchild.pid; wait")
        var failure: Throwable? = null
        var interruptedAfter = false
        val runner = thread {
            try { runCli(engine, "", dir, null, 30_000) } catch (t: Throwable) { failure = t }
            interruptedAfter = Thread.currentThread().isInterrupted
        }
        val pidFile = dir.resolve("grandchild.pid")
        val deadline = System.currentTimeMillis() + 5_000
        while ((!Files.exists(pidFile) || Files.size(pidFile) == 0L) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val pid = pidOf("grandchild.pid")
        runner.interrupt()
        runner.join(10_000)
        assertFalse(runner.isAlive)
        assertTrue(failure is EngineCancelled)
        assertTrue(interruptedAfter)
        // runCli returned, so the grandchild (which ignored SIGTERM) must already be dead.
        assertDead(pid)
    }

    fun testALargeAnswerIsRejectedEvenWhenTheEngineExitsRightAway() {
        if (posixOnly()) return
        repeat(5) {
            val e = failureOf { runCli(sh("head -c 5300000 /dev/zero"), "", dir, null, 30_000) }
            assertEquals(SmartPrReviewBundle.message("ai.tooLarge", 5), e.message)
        }
    }

    fun testAnEngineThatExitsWithoutReadingTheLargePromptStillFinishes() {
        if (posixOnly()) return
        assertEquals("ok", runCli(sh("printf ok"), "x".repeat(2_000_000), dir, null, 10_000))
    }

    fun testAStopConditionEndsTheEngineLikeACancellation() {
        if (posixOnly()) return
        val engine = sh("sleep 60 & echo \$! > grandchild.pid; wait")
        val pidFile = dir.resolve("grandchild.pid")
        val started = System.currentTimeMillis()
        try {
            runCli(engine, "", dir, null, 30_000, stop = { Files.exists(pidFile) && Files.size(pidFile) > 0L })
            fail("expected EngineCancelled")
        } catch (_: EngineCancelled) {
        }
        assertTrue(System.currentTimeMillis() - started < 8_000)
        assertDead(pidOf("grandchild.pid"))
    }

    // ---- models ----

    fun testReadsTheModelsACliListsAsIdNameLines() {
        // The output of `cursor-agent models`, with what other CLIs may add: colors, CRLF, zero-width padding.
        val output = listOf(
            "Available models",
            "",
            "auto - Auto (default)",
            "gpt-5.3-codex-low - Codex 5.3 Low",
            "grok-4.7-low-fast - Grok 4.7  Low Fast\u200b\u200b",
            "\u001b[1mcomposer-2.5\u001b[0m - Composer 2.5",
            "claude-opus-4-8[context=1m,effort=high]",
            "auto - Listed twice",
            "--force - Not a model",
            "",
        ).joinToString("\r\n")
        assertEquals(
            listOf(
                CliModel("auto", "Auto (default)"),
                CliModel("gpt-5.3-codex-low", "Codex 5.3 Low"),
                CliModel("grok-4.7-low-fast", "Grok 4.7 Low Fast"),
                CliModel("composer-2.5", "Composer 2.5"),
                CliModel("claude-opus-4-8[context=1m,effort=high]"),
            ),
            parseModelList(output),
        )
        assertEquals(emptyList<CliModel>(), parseModelList(""))
    }

    fun testAModelNameIsASingleArgumentAndNeverAnOption() {
        for (name in listOf("sonnet", "gpt-5.2", "claude-opus-4-8[context=1m,effort=high,fast=false]", "vendor/model:tag")) assertTrue(name, isModelName(name))
        for (name in listOf("", "-m", "--dangerously-skip-permissions", "two words", "tab\there", "line\nbreak", "x".repeat(201))) assertFalse(name, isModelName(name))
    }

    fun testAsksACliForItsModelsWithTheArgumentsOfTheEngine() {
        if (posixOnly()) return
        val models = listCliModels("sh", listOf("-c", "printf 'Available models\\n\\nauto - Auto (default)\\nother\\n'"), null, 10_000)
        assertEquals(listOf(CliModel("auto", "Auto (default)"), CliModel("other")), models)
        failureOf { listCliModels("sh", listOf("-c", "echo 'not logged in' >&2; exit 1"), null, 10_000) }
    }

    /** Like an npm shim: forwards arguments to a native executable, which parses them with Windows CRT rules. */
    fun testWindowsCmdShimInPathWithSpacesReceivesArgumentsVerbatim() {
        if (!SystemInfo.isWindows) return
        for (folder in listOf("with space/bin", "with space/node_modules/.bin")) {
            val bin = Files.createDirectories(dir.resolve(folder))
            val source = bin.resolve("Arguments.java")
            Files.writeString(source, """
                public class Arguments {
                    public static void main(String[] args) throws Exception {
                        for (String arg : args) System.out.println("ARG:" + arg);
                        System.in.transferTo(System.out);
                    }
                }
            """.trimIndent())
            // The test JVM is the IDE's JBR, which need not include a compiler; setup-java supplies this JDK.
            val java = Path.of(requireNotNull(System.getenv("JAVA_HOME")), "bin", "java.exe")
            Files.writeString(
                bin.resolve("fake-engine.cmd"),
                "@echo off\r\n\"$java\" \"%~dp0Arguments.java\" %*\r\n",
            )
            val env = mapOf("PATH" to bin.toString(), "PATHEXT" to ".COM;.EXE;.BAT;.CMD")
            val path = findExecutable("fake-engine", env, windows = true)!!.toString()
            val args = listOf("plain", "with space", "quote\"inside", "a&b|c^d")
            val out = runCli(Engine("fake", path, args), "the prompt", dir, null, 30_000)
            val lines = out.lines().map { it.trimEnd() }
            for (a in args) assertTrue("missing ARG:$a in:\n$out", "ARG:$a" in lines)
            assertTrue(out, out.contains("the prompt"))
        }
    }

    private fun failureOf(block: () -> Unit): EngineException {
        try {
            block()
        } catch (e: EngineException) {
            return e
        }
        throw AssertionError("expected EngineException")
    }

    private fun pidOf(name: String): Long = Files.readString(dir.resolve(name)).trim().toLong()

    /** A killed process may take a moment to be reaped, and a zombie still reports alive: poll up to 2 s. */
    private fun assertDead(pid: Long) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) Thread.sleep(20)
        assertTrue("process $pid still alive", ProcessHandle.of(pid).map { !it.isAlive }.orElse(true))
    }

    // ---- URL engines ----

    private fun engineError(block: () -> Unit): String {
        try {
            block()
        } catch (e: EngineException) {
            return e.message.orEmpty()
        }
        throw AssertionError("expected an EngineException")
    }

    fun testAUrlEngineSendsThePromptWithTheModelMaxTokensReasoningEffortAndKeyAndDropsTheReasoning() {
        val json = "```json\n{\"summary\":\"ok\",\"findings\":[]}\n```"
        FakeChatServer { completion("<think>maybe { this</think>\n$json") }.use { server ->
            val engine = UrlEngine("qwen", "qwen3.8-27b", server.url, 32768, 8192, "secret", reasoningEffort = "medium")
            assertEquals(24576, engine.maxInputTokens)
            assertEquals("\n$json", runChat(engine, "the prompt", null, 10_000))
            val request = server.requests.single()
            assertEquals(listOf("POST", "/v1/chat/completions", "Bearer secret"), listOf(request.method, request.path, request.authorization))
            assertEquals(
                JsonParser.parseString("""{"model":"qwen3.8-27b","messages":[{"role":"user","content":"the prompt"}],"max_tokens":8192,"stream":false,"reasoning_effort":"medium"}"""),
                JsonParser.parseString(request.body),
            )
            // Engines end up in logs.
            assertFalse(engine.toString(), "secret" in engine.toString())
        }
    }

    fun testAUrlEngineSaysWhyAnAnswerWasCutAKeyRejectedARequestRefusedOrAServerMisread() {
        val answers = ArrayDeque(listOf(
            completion("<think>still thinking", "length"),
            401 to """{"error":{"message":"Invalid API key"}}""",
            404 to """{"error":"model 'nope' not found"}""",
            200 to """{"ok":true}""",
        ))
        val url = FakeChatServer { answers.removeFirst() }.use { server ->
            val engine = UrlEngine("x", "m", server.url, 8192, 4096, null)
            assertEquals(SmartPrReviewBundle.message("ai.outputCut", 4096), engineError { runChat(engine, "p", null, 10_000) })
            // Without a reasoning effort the server's default applies: none is sent.
            assertNull(JsonParser.parseString(server.requests.first().body).asJsonObject["reasoning_effort"])
            assertEquals(SmartPrReviewBundle.message("ai.apiKeyRejected", server.url, 401), engineError { runChat(engine.copy(apiKey = "bad"), "p", null, 10_000) })
            assertEquals(
                SmartPrReviewBundle.message("ai.httpFailed", server.url, 404, "model 'nope' not found"),
                engineError { runChat(engine.copy(model = "nope"), "p", null, 10_000) },
            )
            assertEquals(SmartPrReviewBundle.message("ai.notOpenAi", server.url), engineError { runChat(engine, "p", null, 10_000) })
            server.url
        }
        // The server is gone: nothing listens on its port any more.
        val refused = engineError { runChat(UrlEngine("x", "m", url, 8192, 4096, null), "p", null, 10_000) }
        assertTrue(refused, refused.startsWith(SmartPrReviewBundle.message("ai.unreachable", url, "")))
    }

    fun testAUrlRequestIsAbortedOnTimeoutAndOnCancellation() {
        val release = CountDownLatch(1)
        FakeChatServer { release.await(10, TimeUnit.SECONDS); completion("late") }.use { server ->
            try {
                val engine = UrlEngine("x", "m", server.url, 8192, 4096, null)
                val started = System.currentTimeMillis()
                assertEquals(SmartPrReviewBundle.message("ai.timeout", 300 / 60000.0), engineError { runChat(engine, "p", null, 300) })
                try {
                    runChat(engine, "p", EmptyProgressIndicator().apply { cancel() }, 10_000)
                    fail("a cancelled review must not wait for the answer")
                } catch (_: EngineCancelled) {
                }
                assertTrue(System.currentTimeMillis() - started < 5_000)
            } finally {
                release.countDown()
            }
        }
    }

    fun testListsTheModelsOfAUrlEngineSendingNoKeyWhenThereIsNone() {
        FakeChatServer { 200 to """{"object":"list","data":[{"id":"qwen3.8-27b"},{"id":"openai/gpt-oss-20b"},{"id":"--bad"},{},null]}""" }.use { server ->
            assertEquals(listOf(CliModel("qwen3.8-27b"), CliModel("openai/gpt-oss-20b")), listUrlModels(server.url, null, 10_000))
            assertEquals("/v1/models", server.requests.single().path)
            assertNull(server.requests.single().authorization)
        }
    }

    fun testSplitsPromptsToFitTheWindowWithAMarginSendsATooLargeFileAsDiffOnlyAndSkipsWhatStillDoesNotFit() {
        // Tokens are estimated at 3 characters each; each file is 40 characters with its contents.
        val make = { batch: List<String>, withContents: Boolean -> batch.joinToString("|") { if (withContents) it.padEnd(40, '.') else it } }
        val split = promptsWithinLimit(listOf("a.ts", "b.ts", "c.ts", "d.ts"), 34, make)
        assertEquals(listOf(listOf("a.ts", "b.ts"), listOf("c.ts", "d.ts")), split.prompts.map { p -> p.split("|").map { it.take(4) } })
        assertEquals(emptyList<String>(), split.skipped)
        // 81 characters (27 tokens) fit 28 tokens, but not 28 minus the margin.
        assertEquals(2, promptsWithinLimit(listOf("a.ts", "b.ts"), 28, make).prompts.size)
        assertEquals(PromptBatches(listOf("diff"), emptyList()), promptsWithinLimit(listOf("big.ts"), 20) { _, withContents -> if (withContents) "x".repeat(300) else "diff" })
        assertEquals(PromptBatches(emptyList(), listOf("huge.ts")), promptsWithinLimit(listOf("huge.ts"), 20) { _, _ -> "x".repeat(300) })
    }
}
