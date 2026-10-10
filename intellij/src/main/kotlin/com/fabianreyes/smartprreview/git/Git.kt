package com.fabianreyes.smartprreview.git

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

data class Remote(val name: String, val url: String)

class GitException(message: String) : RuntimeException(message)

/** Runs git in a repository. Blocking: never call on the EDT. */
class Git(val root: Path) {
    /** Runs `git args` in [root]; stdout on success, GitException with stderr otherwise. */
    fun run(vararg args: String, env: Map<String, String> = emptyMap()): String {
        val process = ProcessBuilder(listOf("git") + args).directory(root.toFile()).apply { environment().putAll(env) }.start()
        process.outputStream.close()
        // Read both streams at once: a full stderr pipe would block git.
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readBytes().toString(Charsets.UTF_8) }
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        if (code != 0) throw GitException("git ${args.firstOrNull() ?: ""}: ${stderr.get().trim().ifEmpty { "exit code $code" }}")
        return stdout
    }

    private fun tryRun(vararg args: String): String? = try {
        run(*args)
    } catch (e: GitException) {
        null
    }

    /** The remote that hosts pull requests: `upstream` (fork workflows), then `origin`, then the first one. */
    fun pickRemote(): Remote? {
        val names = run("remote").lines().filter { it.isNotEmpty() }
        val name = listOf("upstream", "origin").firstOrNull { it in names } ?: names.firstOrNull() ?: return null
        return Remote(name, run("remote", "get-url", name).trim())
    }

    fun fetchRefs(remote: String, refspecs: List<String>) {
        run("fetch", "--no-tags", remote, *refspecs.toTypedArray())
    }

    fun hasCommit(sha: String): Boolean = tryRun("cat-file", "-e", "$sha^{commit}") != null

    fun mergeBase(a: String, b: String): String = run("merge-base", a, b).trim()

    fun changedFiles(base: String, head: String): List<ChangedFile> = parseNameStatus(run("diff", "--name-status", "-z", "-M", base, head))

    /** Content of `path` at `sha`, or "" when the file does not exist there. */
    fun showFile(sha: String, path: String): String = tryRun("show", "$sha:$path") ?: ""

    /** Head-side lines covered by the diff hunks of `file` (the lines GitHub accepts review comments on). */
    fun diffRanges(base: String, head: String, file: ChangedFile): List<LineRange> = parseHunkRanges(fileDiff(base, head, file, 3))

    /** Head-side lines the pull request adds or changes in `file`: diffRanges without the context lines. */
    fun changedRanges(base: String, head: String, file: ChangedFile): List<LineRange> = parseChangedRanges(fileDiff(base, head, file, 0))

    private fun fileDiff(base: String, head: String, file: ChangedFile, context: Int): String {
        val paths = listOfNotNull(file.oldPath, file.path).map { ":(literal)$it" }
        // Pin what GitHub's diff assumes; user config (color, context, external diff tools) must not change the ranges.
        return run("diff", "--no-color", "--no-ext-diff", "--no-textconv", "-U$context", "--inter-hunk-context=0", "-M", base, head, "--", *paths.toTypedArray())
    }

    fun isDirty(): Boolean = run("status", "--porcelain", "--untracked-files=no").isNotBlank()

    fun headSha(): String = run("rev-parse", "HEAD").trim()

    fun currentBranch(): String? = tryRun("symbolic-ref", "--quiet", "--short", "HEAD")?.trim()

    private fun branchExists(branch: String): Boolean = tryRun("rev-parse", "--verify", "--quiet", "refs/heads/$branch") != null

    /** False when `branch` has commits `sha` lacks, e.g. after the pull request was force-pushed. */
    fun canFastForward(branch: String, sha: String): Boolean =
        !branchExists(branch) || tryRun("merge-base", "--is-ancestor", "refs/heads/$branch", sha) != null

    /**
     * Checks out `branch` at `sha`: creates the branch or fast-forwards it. With `reset`, points an existing
     * branch at `sha`, discarding commits only that branch has. `upstream` ("origin/x", already fetched) becomes
     * the branch's tracking branch, so that pull and push work on it.
     */
    fun checkout(branch: String, sha: String, reset: Boolean = false, upstream: String? = null) {
        if (reset || !branchExists(branch)) {
            run("checkout", "-B", branch, sha)
        } else {
            run("checkout", branch)
            run("merge", "--ff-only", sha)
        }
        if (upstream != null) run("branch", "--set-upstream-to=$upstream", branch)
    }

    /** Unified diff of `paths` (repository-relative, taken literally) with pinned options, for prompts. */
    fun diffText(base: String, head: String, paths: List<String>): String =
        run("diff", "--no-color", "--no-ext-diff", "--no-textconv", "-U3", "-M", base, head, "--", *paths.map { ":(literal)$it" }.toTypedArray())

    /**
     * Detached worktree of `sha` at `dir` (the AI engines read the pull request there, never the user's
     * checkout). Without the repository's hooks (e.g. a post-checkout that installs dependencies) and without
     * downloading Git LFS files.
     */
    fun addWorktree(dir: Path, sha: String) {
        run("-c", "core.hooksPath=$DEV_NULL", "worktree", "add", "--detach", "--quiet", dir.toString(), sha, env = mapOf("GIT_LFS_SKIP_SMUDGE" to "1"))
    }

    fun removeWorktree(dir: Path) {
        run("worktree", "remove", "--force", dir.toString())
    }

    /** Forgets worktrees whose folders are gone, e.g. after the IDE was closed during a review. */
    fun pruneWorktrees() {
        tryRun("worktree", "prune")
    }

    companion object {
        private val DEV_NULL = if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"

        /**
         * The repository containing `dir`, spelled through `dir` when possible: git reports the resolved path,
         * but a project opened through a symlink uses the symlinked one. Null outside a repository or without git.
         */
        fun open(dir: Path): Git? {
            val top = try {
                Git(dir).run("rev-parse", "--show-toplevel").trim()
            } catch (e: GitException) {
                return null
            } catch (e: IOException) {
                return null // git is not installed
            }
            if (top.isEmpty()) return null
            val root = try {
                val up = dir.toRealPath().relativize(Path.of(top).toRealPath())
                if (up.toString().isEmpty() || up.all { it.toString() == ".." }) dir.resolve(up).normalize() else Path.of(top)
            } catch (e: IOException) {
                Path.of(top)
            } catch (e: IllegalArgumentException) {
                Path.of(top)
            }
            return Git(root)
        }
    }
}
