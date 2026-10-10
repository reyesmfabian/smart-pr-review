package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.providers.FakeProvider
import com.fabianreyes.smartprreview.providers.Side
import com.fabianreyes.smartprreview.providers.Thread
import com.fabianreyes.smartprreview.ui.diff.revisionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.io.path.writeText

class PlacementTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val mergeBase = "m".repeat(40)
    private val head = "b".repeat(40)

    private fun thread(
        side: Side = Side.HEAD, line: Int? = 5, startLine: Int? = null, outdated: Boolean = false,
        original: Thread.Original? = null, path: String = "a.kt",
    ) = Thread("t", path, side, line, startLine, outdated, original, false, emptyList())

    private fun pr(vararg files: ChangedFile): OpenPullRequest {
        val p = FakeProvider().apply { headSha = head }.getPullRequest(1)
        return OpenPullRequest(p, mergeBase, files.toList(), emptyList(), mutableSetOf(), DraftStore(MemoryStore(), "k"), false)
    }

    @Test
    fun `threadLines of an outdated thread come from original`() {
        assertEquals(7 to 3, threadLines(thread(line = null, outdated = true, original = Thread.Original("c".repeat(40), 7, 3))))
        assertNull(threadLines(thread(line = null, outdated = true)))
    }

    @Test
    fun `threadLines of a live thread`() {
        assertEquals(5 to null, threadLines(thread()))
        assertEquals(5 to 2, threadLines(thread(startLine = 2)))
        assertNull(threadLines(thread(line = null)))
    }

    @Test
    fun `base thread on a renamed file goes to the old path at the merge base`() {
        val c = pr(ChangedFile(ChangedFile.Status.R, "new.kt", "old.kt"))
        assertEquals(Placement(Side.BASE, mergeBase, "old.kt"), threadPlacement(c, thread(side = Side.BASE, path = "new.kt")))
    }

    @Test
    fun `base thread on a file that was not renamed keeps its path`() {
        val c = pr(ChangedFile(ChangedFile.Status.M, "a.kt"))
        assertEquals(Placement(Side.BASE, mergeBase, "a.kt"), threadPlacement(c, thread(side = Side.BASE)))
    }

    @Test
    fun `outdated head thread goes to its original commit`() {
        val sha = "c".repeat(40)
        val t = thread(line = null, outdated = true, original = Thread.Original(sha, 7))
        assertEquals(Placement(Side.HEAD, sha, "a.kt"), threadPlacement(pr(), t))
    }

    @Test
    fun `head thread goes to the head`() {
        assertEquals(Placement(Side.HEAD, head, "a.kt"), threadPlacement(pr(), thread()))
    }

    @Test
    fun `suggestionBlock grows its fence past the longest backtick run`() {
        assertEquals("```suggestion\nx\n```", suggestionBlock("x"))
        assertEquals("````suggestion\na ``` b\n````", suggestionBlock("a ``` b"))
        assertEquals("`````suggestion\na ```` b\n`````", suggestionBlock("a ```` b"))
    }

    @Test
    fun `revisionText refuses anything but a full hex sha`() {
        val repo = tmp.newFolder("repo").toPath()
        val git = Git(repo)
        git.run("init", "-q", "-b", "main")
        git.run("config", "user.email", "t@example.com")
        git.run("config", "user.name", "t")
        git.run("config", "commit.gpgsign", "false")
        repo.resolve("a.txt").writeText("hello\n")
        git.run("add", "-A")
        git.run("commit", "-q", "-m", "one")
        assertEquals("", revisionText(git, "--output=x", "a.txt"))
        assertEquals("", revisionText(git, "HEAD", "a.txt"))
        assertEquals("hello\n", revisionText(git, git.headSha(), "a.txt"))
    }

    @Test
    fun `a suggestion body is the comment, then the suggestion block`() {
        assertEquals("Use val\n\n```suggestion\nval x = 1\n```", suggestionBody("  Use val \n", "val x = 1"))
        assertEquals("```suggestion\nval x = 1\n```", suggestionBody("   ", "val x = 1"))
    }
}
