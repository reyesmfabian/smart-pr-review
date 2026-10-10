package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.git.Git
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeText

class ReviewWorktreesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var git: Git
    private lateinit var w: ReviewWorktree

    @Before fun init() {
        git = Git(tmp.newFolder("repo with spaces").toPath())
        git.run("init", "-q", "-b", "main")
        git.run("config", "user.email", "t@example.com")
        git.run("config", "user.name", "t")
        git.run("config", "commit.gpgsign", "false")
        git.root.resolve("a.txt").writeText("base")
        git.run("add", "-A")
        git.run("commit", "-q", "-m", "base")
        git.root.resolve("local.txt").writeText("keep the original checkout")
        w = createReviewWorktree(git, 7, git.headSha())
    }

    @Test fun `creates a detached worktree with shared persistent registry and removes it safely`() {
        assertEquals("main", git.currentBranch())
        assertNull(Git(w.path).currentBranch())
        assertEquals(git.headSha(), Git(w.path).headSha())
        assertEquals(listOf(w), reviewWorktrees(Git(w.path)))
        removeReviewWorktree(git, reviewWorktrees(git).single())
        assertFalse(w.path.exists())
        assertTrue(reviewWorktrees(git).isEmpty())
        assertTrue(git.root.resolve("local.txt").exists())
    }

    @Test fun `refuses to delete the current project or an unregistered path`() {
        assertThrows(IllegalStateException::class.java) { removeReviewWorktree(Git(w.path), w) }
        assertThrows(IllegalStateException::class.java) { checkReviewWorktree(git, w.copy(path = git.root)) }
        assertTrue(w.path.exists())
    }

    @Test fun `keeps tracked untracked and ignored local data`() {
        val target = Git(w.path)
        w.path.resolve("a.txt").writeText("edited")
        assertThrows(IllegalStateException::class.java) { removeReviewWorktree(git, w) }
        target.run("restore", "a.txt")
        w.path.resolve("local.txt").writeText("keep")
        assertThrows(IllegalStateException::class.java) { removeReviewWorktree(git, w) }
        git.root.resolve(".git/info/exclude").writeText("local.txt\n")
        assertThrows(IllegalStateException::class.java) { removeReviewWorktree(git, w) }
        assertTrue(w.path.resolve("local.txt").exists())
    }

    @Test fun `keeps local commits even if the user resets back to the review head`() {
        val target = Git(w.path)
        target.run("commit", "--allow-empty", "-m", "keep this")
        assertThrows(IllegalStateException::class.java) { removeReviewWorktree(git, w) }
        target.run("reset", "--hard", w.headSha)
        assertThrows(IllegalStateException::class.java) { removeReviewWorktree(git, w) }
    }

    @Test fun `failed creation removes only its own metadata`() {
        assertThrows(RuntimeException::class.java) { createReviewWorktree(git, 8, "0".repeat(40)) }
        assertThrows(IllegalArgumentException::class.java) { createReviewWorktree(git, 8, "--help") }
        assertEquals(listOf(w), reviewWorktrees(git))
    }

    @Test fun `removing one worktree preserves the others and handles json from vscode`() {
        val other = createReviewWorktree(git, 8, git.headSha())
        // Different JSON whitespace must survive reading and exact removal (shared with the TS implementation).
        val record = w.record.replace(",", ", ")
        git.run("config", "--local", "--fixed-value", "--unset-all", "smart-pr-review.worktree", w.record)
        git.run("config", "--local", "--add", "smart-pr-review.worktree", record)
        removeReviewWorktree(git, reviewWorktrees(git).single { it.prId == 7 })
        assertEquals(listOf(other), reviewWorktrees(git))
        assertTrue(Files.exists(other.path))
    }
}
