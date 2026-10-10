package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.git.Git
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.UUID

private const val WORKTREE_KEY = "smart-pr-review.worktree"
private val COMMIT = Regex("[a-fA-F0-9]{40,64}")

/** Original JSON is retained: VS Code and IntelliJ share the registry in the common local git config. */
data class ReviewWorktree(val path: Path, val headSha: String, val prId: Int, val record: String) {
    override fun toString() = "#$prId · ${headSha.take(8)} · $path"
}

fun reviewWorktrees(git: Git): List<ReviewWorktree> =
    git.run("config", "--local", "--null", "--list").split('\u0000').filter { it.startsWith("$WORKTREE_KEY\n") }.map { entry ->
        val record = entry.substringAfter('\n')
        val value = JsonParser.parseString(record).asJsonObject
        val path = Path.of(value.get("path").asString)
        val head = value.get("headSha").asString
        val id = value.get("prId").asString.toInt()
        check(path.isAbsolute && COMMIT.matches(head) && id > 0) { message("worktree.invalid") }
        ReviewWorktree(path, head, id, record)
    }

fun createReviewWorktree(git: Git, prId: Int, headSha: String): ReviewWorktree {
    require(prId > 0 && COMMIT.matches(headSha)) { message("worktree.invalid") }
    val path = git.root.resolveSibling("${git.root.fileName}-review-$prId-${UUID.randomUUID().toString().take(8)}")
    val record = JsonObject().apply {
        addProperty("path", path.toString())
        addProperty("headSha", headSha)
        addProperty("prId", prId)
    }.toString()
    git.run("config", "--local", "--add", WORKTREE_KEY, record)
    try {
        git.addWorktree(path, headSha)
    } catch (e: Exception) {
        git.run("config", "--local", "--fixed-value", "--unset-all", WORKTREE_KEY, record)
        throw e
    }
    return ReviewWorktree(path, headSha, prId, record)
}

fun checkReviewWorktree(git: Git, worktree: ReviewWorktree) {
    val paths = git.run("worktree", "list", "--porcelain", "-z").split('\u0000')
        .filter { it.startsWith("worktree ") }.map { it.removePrefix("worktree ") }
    check(worktree in reviewWorktrees(git) && worktree.path.toRealPath().toString() in paths) { message("worktree.unregistered") }
}

fun removeReviewWorktree(git: Git, worktree: ReviewWorktree) {
    checkReviewWorktree(git, worktree)
    check(git.root.toRealPath() != worktree.path.toRealPath()) { message("worktree.inside") }
    val target = Git(worktree.path)
    // Git removes ignored files silently, and even a reset could leave new commits only in this worktree's reflog.
    val status = target.run("status", "--porcelain", "--untracked-files=all", "--ignored")
    val history = target.run("reflog", "--format=%H", "HEAD").lines().filter { it.isNotBlank() }
    check(status.isBlank() && target.headSha() == worktree.headSha && history.all { it == worktree.headSha }) { message("worktree.dirty") }
    git.run("worktree", "remove", "--", worktree.path.toString())
    git.run("config", "--local", "--fixed-value", "--unset-all", WORKTREE_KEY, worktree.record)
}
