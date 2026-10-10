package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle
import com.intellij.execution.CommandLineUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** A CLI engine to run (not the stored setting, `settings.Engine`). */
data class Engine(val id: String, val executable: String, val args: List<String>)

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
                indicator?.isCanceled == true -> EngineCancelled()
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
