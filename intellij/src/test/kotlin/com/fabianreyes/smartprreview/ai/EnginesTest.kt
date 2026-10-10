package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import javax.tools.ToolProvider

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

    // ---- windowsCommandLine ----

    fun testQuotesWindowsCmdShimsLikeCrossSpawn() {
        val line = windowsCommandLine("C:\\Program Files\\nodejs\\codex.cmd", listOf("exec", "-c", "approval_policy=\"never\"", "a b"), "cmd.exe")
        assertEquals(
            "cmd.exe" to listOf("/d", "/s", "/c", "\"C:\\Program^ Files\\nodejs\\codex.cmd ^\"exec^\" ^\"-c^\" ^\"approval_policy=\\^\"never\\^\"^\" ^\"a^ b^\"\""),
            line,
        )
    }

    fun testEscapesLocalNodeModulesBinShimsTwice() {
        val (_, args) = windowsCommandLine("C:\\p\\node_modules\\.bin\\x.cmd", listOf("a b"), "cmd.exe")
        assertEquals("\"C:\\p\\node_modules\\.bin\\x.cmd ^^^\"a^^^ b^^^\"\"", args.last())
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

    /** Like an npm shim: forwards arguments to a native executable, which parses them with Windows CRT rules. */
    fun testWindowsCmdShimInPathWithSpacesReceivesArgumentsVerbatim() {
        if (!SystemInfo.isWindows) return
        val bin = Files.createDirectories(dir.resolve("with space").resolve("bin"))
        val source = bin.resolve("Arguments.java")
        Files.writeString(source, """
            public class Arguments {
                public static void main(String[] args) throws Exception {
                    for (String arg : args) System.out.println("ARG:" + arg);
                    System.in.transferTo(System.out);
                }
            }
        """.trimIndent())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, source.toString()))
        val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
        Files.writeString(
            bin.resolve("fake-engine.cmd"),
            "@echo off\r\n\"$java\" -cp \"%~dp0.\" Arguments %*\r\n",
        )
        val env = mapOf("PATH" to bin.toString(), "PATHEXT" to ".COM;.EXE;.BAT;.CMD")
        val path = findExecutable("fake-engine", env, windows = true)!!.toString()
        val args = listOf("plain", "with space", "quote\"inside", "a&b|c^d")
        val out = runCli(Engine("fake", path, args), "the prompt", dir, null, 30_000)
        val lines = out.lines().map { it.trimEnd() }
        for (a in args) assertTrue("missing ARG:$a in:\n$out", "ARG:$a" in lines)
        assertTrue(out, out.contains("the prompt"))
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
}
