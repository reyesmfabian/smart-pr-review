package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle
import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.git.LineRange
import com.fabianreyes.smartprreview.review.OpenPullRequest
import com.fabianreyes.smartprreview.review.ReviewSession
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val LOG = logger<AiReviewOptions>()

data class AiReviewOptions(
    val session: ReviewSession,
    /** The pull request that was open (and checked) when the review was asked for. */
    val pr: OpenPullRequest,
    val profiles: List<Profile>,
    val engine: Engine,
    /** Folder for the temporary worktrees (tests use a temp dir). */
    val worktrees: Path,
    val timeoutMs: Long,
    val labelComments: Boolean,
    /** Language of the comments and summary (see aiLanguage). */
    val language: String,
    val indicator: ProgressIndicator?,
)

data class AiReviewResult(
    val drafts: Int,
    /** Profiles that produced an answer (even an empty one). */
    val reviewed: List<String>,
    val failures: List<Failure>,
) {
    data class Failure(val profile: String, val error: String)
}

/** How the engine is named in progress, summaries and comment footers. */
fun engineLabel(engine: Engine): String = engine.id

/** Content git shows for a binary file. */
fun isBinary(text: String): Boolean = '\u0000' in text

/**
 * Reviews the open pull request with each profile in parallel and turns the findings into drafts plus a
 * summary section per profile. The engine reads a detached worktree of the head, removed at the end.
 */
fun runAiReview(o: AiReviewOptions): AiReviewResult {
    val c = o.pr
    val git = o.session.git
    if (o.profiles.isEmpty()) return AiReviewResult(0, emptyList(), emptyList())
    // The creation time in the name lets cleanupWorktrees find folders left by an IDE that closed mid-review.
    val worktree = o.worktrees.resolve("pr-${c.pr.id}-${System.currentTimeMillis()}-${UUID.randomUUID()}")
    Files.createDirectories(o.worktrees)
    try {
        git.addWorktree(worktree, c.pr.headSha)
        removeAgentConfiguration(worktree, Git(worktree))
        val outcomes = runProfiles(o, c, git, worktree)
        // Every engine has exited before the worktree is removed, even when one was cancelled.
        if (outcomes.any { it is Outcome.Cancelled }) throw EngineCancelled()
        val failures = mutableListOf<AiReviewResult.Failure>()
        val reviewed = mutableListOf<String>()
        var drafts = 0
        for ((profile, outcome) in o.profiles.zip(outcomes)) {
            when (outcome) {
                is Outcome.Failed -> failures += AiReviewResult.Failure(profile.name, outcome.error)
                // Profiles that match no changed file did not review anything.
                Outcome.Skipped, Outcome.Cancelled -> {}
                is Outcome.Answer -> {
                    // Findings belong to the pull request (and head) that was reviewed, not to whatever is open now.
                    val applied = applyOutput(o, c, git, profile, outcome.output)
                    if (applied == null) {
                        failures += AiReviewResult.Failure(profile.name, SmartPrReviewBundle.message("ai.prChanged"))
                        continue
                    }
                    drafts += applied
                    reviewed += profile.name
                }
            }
        }
        return AiReviewResult(drafts, reviewed, failures)
    } finally {
        // Cleanup must never replace the result (or the original exception) with its own failure.
        try {
            // A failed addWorktree may leave nothing registered; remove what exists either way.
            if (Files.exists(worktree)) git.removeWorktree(worktree)
        } catch (e: Exception) {
            LOG.warn("Could not remove $worktree: $e")
        }
        try {
            if (Files.exists(worktree, LinkOption.NOFOLLOW_LINKS)) deleteWithoutFollowing(worktree)
            git.pruneWorktrees()
        } catch (e: Exception) {
            LOG.warn("Could not clean up $worktree: $e")
        }
    }
}

private sealed interface Outcome {
    data object Skipped : Outcome
    data object Cancelled : Outcome
    data class Answer(val output: ReviewOutput) : Outcome
    data class Failed(val error: String) : Outcome
}

private fun runProfiles(o: AiReviewOptions, c: OpenPullRequest, git: Git, worktree: Path): List<Outcome> {
    val pool = Executors.newFixedThreadPool(o.profiles.size)
    try {
        // invokeAll waits for every profile (allSettled): one failing does not stop the others.
        return pool.invokeAll(o.profiles.map { profile -> Callable { reviewWithProfile(o, c, git, profile, worktree) } }).map {
            try {
                it.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                Outcome.Failed(e.cause?.message ?: e.cause.toString())
            }
        }
    } finally {
        // On interruption the workers are interrupted and must have exited (engines killed) before the worktree goes.
        pool.shutdownNow()
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) LOG.warn("The review workers did not stop within 30 s")
    }
}

private fun reviewWithProfile(o: AiReviewOptions, c: OpenPullRequest, git: Git, profile: Profile, worktree: Path): Outcome {
    val files = c.files.filter { it.status != ChangedFile.Status.D && appliesTo(profile.include, it.path) }.map { it.path }
    if (files.isEmpty()) return Outcome.Skipped
    // With the old path of renamed files, so a rename is not sent as a whole new file.
    val diffPaths = c.files.filter { it.path in files }.flatMap { listOfNotNull(it.oldPath, it.path) }
    val prompt = buildPrompt(
        ProfileContent(profile.name, profile.description, profile.include, profile.body),
        files,
        git.diffText(c.mergeBase, c.pr.headSha, diffPaths),
        PromptContext(o.language, c.pr.title, c.pr.body),
    )
    return try {
        val answer = runCli(o.engine, prompt, worktree, o.indicator, o.timeoutMs)
        try {
            Outcome.Answer(parseReviewOutput(answer))
        } catch (e: IllegalStateException) {
            LOG.warn("Answer for ${profile.name} with ${engineLabel(o.engine)}:\n$answer")
            throw e
        }
    } catch (e: EngineCancelled) {
        Outcome.Cancelled
    } catch (e: Exception) {
        val stderr = (e as? EngineException)?.stderr?.trim().orEmpty()
        LOG.warn("${profile.name} with ${engineLabel(o.engine)}: ${e.message}${if (stderr.isNotEmpty()) "\n$stderr" else ""}")
        Outcome.Failed(e.message ?: e.toString())
    }
}

/** Applies [output] to [c], the pull request that was reviewed; null when it is no longer the open one (nothing applied). */
private fun applyOutput(o: AiReviewOptions, c: OpenPullRequest, git: Git, profile: Profile, output: ReviewOutput): Int? {
    val paths = c.files.filter { it.status != ChangedFile.Status.D }.map { it.path }
    val findings = output.findings.map { it.copy(file = changedPath(it.file, paths)) }
    val commentable = mutableMapOf<String, List<LineRange>?>()
    val changed = mutableMapOf<String, List<LineRange>>()
    val lineCounts = mutableMapOf<String, Int>()
    for (path in findings.map { it.file }.toSet()) {
        val file = c.files.find { it.path == path && it.status != ChangedFile.Status.D } ?: continue
        commentable[path] = o.session.commentableRanges(c, path)
        changed[path] = git.changedRanges(c.mergeBase, c.pr.headSha, file)
        lineCounts[path] = countLines(git.showFile(c.pr.headSha, path))
    }
    val placement = placeFindings(findings, PlacementContext(profile.name, engineLabel(o.engine), commentable, changed, lineCounts, o.labelComments))
    for (f in placement.discarded) LOG.warn("${profile.name}: dropped a finding about ${f.file}, which is not part of the pull request")
    for (f in placement.unchanged) LOG.warn("${profile.name}: dropped a finding on ${f.file}:${f.line}, a line the pull request does not change: ${f.message}")
    // The git calls above take a while: recheck right before writing, and write to c's own store.
    if (o.session.current !== c) return null
    for (d in placement.drafts) o.session.addDraftTo(c, d.path, d.startLine, d.endLine, d.body, d.origin)
    val summary = (listOf(output.summary) + placement.summaryLines).filter { it.isNotEmpty() }.joinToString("\n\n")
    if (summary.isNotEmpty()) o.session.appendSummaryTo(c, "### ${profile.name} (${engineLabel(o.engine)})\n\n$summary")
    return placement.drafts.size
}

/** The changed file a finding names; engines sometimes use its absolute path in the worktree. */
private fun changedPath(file: String, paths: List<String>): String =
    if (file in paths) file else paths.filter { file.endsWith("/$it") }.maxByOrNull { it.length } ?: file

private fun countLines(text: String): Int = if (text.isEmpty()) 0 else text.split("\n").size - (if (text.endsWith("\n")) 1 else 0)

/** Folders and files of agent configuration and instructions that CLIs load from a repository, case-folded. */
private val AGENT_FOLDER_NAMES = listOf(".claude", ".cursor", ".codex", ".gemini")
private val AGENT_FILE_NAMES = listOf(".mcp.json", ".cursorrules", "claude.md", "claude.local.md", "agents.md", "agents.override.md", "gemini.md", "copilot-instructions.md")

/**
 * Folds like case-insensitive filesystems do (a name spelled with U+017F opens as its ASCII twin on APFS):
 * matching more than the filesystem would only deletes more, which is safe.
 */
private fun fold(name: String) = name.uppercase(Locale.ROOT).lowercase(Locale.ROOT)
private val AGENT_FOLDERS = AGENT_FOLDER_NAMES.map(::fold).toSet()
private val AGENT_FILES = AGENT_FILE_NAMES.map(::fold).toSet()

/**
 * The worktree is the pull request's head, so its agent configuration (hooks, MCP servers, rules,
 * instructions) is written by the pull request author: remove it before an engine starts there. Names
 * match in any case and under the filesystem's own folding (on macOS and Windows a CLI opening AGENTS.md
 * gets AGENTS.MD), and symbolic links go too: one could make a file outside the worktree look like part
 * of the pull request. Links go first, and nothing is deleted through a symlinked parent folder (a
 * case-insensitive checkout can leave a link where the index also names a folder).
 * [git] runs in [worktree].
 */
fun removeAgentConfiguration(worktree: Path, git: Git, warn: (String) -> Unit = LOG::warn) {
    val realWorktree = worktree.toRealPath()
    // "<mode> <object> <stage>\t<path>"; mode 120000 is a symbolic link.
    val entries = git.run("ls-files", "-z", "--cached", "--stage").split('\u0000').filter { it.isNotEmpty() }
    val links = linkedSetOf<String>()
    val doomed = linkedSetOf<String>()
    for (entry in entries) {
        val file = entry.substringAfter('\t')
        val parts = file.split('/')
        if (entry.startsWith("120000 ")) links += file
        val folder = parts.indexOfFirst { fold(it) in AGENT_FOLDERS }
        if (folder >= 0) doomed += parts.take(folder + 1).joinToString("/")
        else if (fold(parts.last()) in AGENT_FILES) doomed += file
    }
    links.forEach { deleteInside(realWorktree, worktree.resolve(it), warn) }
    doomed.forEach { deleteInside(realWorktree, worktree.resolve(it), warn) }
    // What the filesystem resolves under the canonical names, whatever the index spells; and any link the walk meets.
    Files.walkFileTree(worktree, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            for (name in AGENT_FOLDER_NAMES + AGENT_FILE_NAMES) {
                val candidate = dir.resolve(name)
                if (existsNoFollow(candidate)) deleteInside(realWorktree, candidate, warn)
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isSymbolicLink) deleteInside(realWorktree, file, warn)
            return FileVisitResult.CONTINUE
        }

        // Fails closed: a folder that cannot be read may hide agent configuration.
        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = if (isGone(exc)) FileVisitResult.CONTINUE else throw exc
    })
}

/** Only these mean "not there"; any other filesystem error must stop the sanitizing (the review then fails and the worktree is removed). */
private fun isGone(e: IOException) =
    e is NoSuchFileException || e is NotDirectoryException || (e is FileSystemException && e.reason?.contains("Not a directory") == true)

/** Whether `path` exists (a link counts as itself); throws on any error other than "not there". */
private fun existsNoFollow(path: Path): Boolean = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    true
} catch (e: IOException) {
    if (isGone(e)) false else throw e
}

/** Deletes `path` only when its parent folder, links resolved, is inside the worktree. */
private fun deleteInside(realWorktree: Path, path: Path, warn: (String) -> Unit) {
    val parent = try {
        path.parent.toRealPath()
    } catch (e: IOException) {
        if (isGone(e)) return // the parent is gone: so is the path
        throw e
    }
    if (!parent.startsWith(realWorktree)) {
        warn("Not deleting $path: it is reached through a link out of the worktree")
        return
    }
    deleteWithoutFollowing(path)
}

/** Deletes `path` (a link is deleted itself, never followed; a missing path is fine). */
internal fun deleteWithoutFollowing(path: Path) {
    // walkFileTree does not follow links unless asked to: a symbolic link reaches visitFile and only the link is deleted.
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
            if (isGone(exc)) FileVisitResult.CONTINUE else throw exc

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

private val WORKTREE_NAME = Regex("""^pr-\d+-(\d+)-""")

/**
 * Removes review worktrees older than [olderThanMs] (left when the IDE closed during a review) and forgets
 * missing ones. Younger folders may belong to a review running in another window.
 */
fun cleanupWorktrees(git: Git, dir: Path, olderThanMs: Long) {
    val names = try {
        Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }
    } catch (e: IOException) {
        emptyList()
    }
    for (name in names) {
        val created = WORKTREE_NAME.find(name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
        if (System.currentTimeMillis() - created < olderThanMs) continue
        val folder = dir.resolve(name)
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue
        try {
            git.removeWorktree(folder)
        } catch (_: Exception) {
        }
        deleteWithoutFollowing(folder)
    }
    git.pruneWorktrees()
}
