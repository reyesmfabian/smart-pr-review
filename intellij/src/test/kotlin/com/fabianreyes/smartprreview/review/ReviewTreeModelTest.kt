package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.PullRequest
import com.fabianreyes.smartprreview.providers.PullRequestSummary
import com.fabianreyes.smartprreview.providers.Side
import com.fabianreyes.smartprreview.providers.Thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReviewTreeModelTest {
    private val sha = "b".repeat(40)

    private fun thread(id: String, path: String, line: Int, resolved: Boolean) =
        Thread(id, path, Side.HEAD, line, null, false, null, resolved, emptyList())

    private fun open(
        viewed: Set<String>, drafts: List<Draft> = emptyList(), threads: List<Thread> = emptyList(),
        paths: List<String> = listOf("a.kt", "b.kt"),
    ): OpenPullRequest {
        val store = DraftStore(MemoryStore(), "k")
        drafts.forEach(store::add)
        val pr = PullRequest(PullRequestSummary(1, "t", "ana", "", "u"), "", false, sha, sha, emptyList(), null, "pr/1", null, "n")
        return OpenPullRequest(pr, sha, paths.map { ChangedFile(ChangedFile.Status.M, it) }, threads, viewed.toMutableSet(), store, false)
    }

    private fun draft(path: String, line: Int) = Draft("", path, line, line, null, null, "x", sha, DraftOrigin.Manual)

    @Test
    fun `progress counts the viewed files`() {
        assertEquals(message("tree.progress", 1, 2), currentPullRequestModel(open(setOf("a.kt"))).progressText)
        assertEquals(1, currentPullRequestModel(open(setOf("a.kt"))).viewedCount)
        assertEquals(message("tree.progress", 0, 2), currentPullRequestModel(open(setOf("gone.kt"))).progressText)
        assertEquals(0, currentPullRequestModel(open(setOf("gone.kt"))).viewedCount)
    }

    @Test
    fun `progress says all when every file is viewed`() {
        assertEquals(message("tree.progressAll", 2), currentPullRequestModel(open(setOf("a.kt", "b.kt"))).progressText)
    }

    @Test
    fun `drafts are sorted by path then line`() {
        val m = currentPullRequestModel(open(emptySet(), listOf(draft("b.kt", 1), draft("a.kt", 9), draft("a.kt", 2))))
        assertEquals(listOf("a.kt" to 2, "a.kt" to 9, "b.kt" to 1), m.drafts.map { it.path to it.startLine })
    }

    @Test
    fun `threads split into active and resolved, each by position`() {
        val m = currentPullRequestModel(
            open(emptySet(), threads = listOf(thread("1", "b.kt", 1, false), thread("2", "a.kt", 5, true), thread("3", "a.kt", 1, false))),
        )
        assertEquals(listOf("3", "1"), m.active.map { it.id })
        assertEquals(listOf("2"), m.resolved.map { it.id })
    }

    @Test
    fun `files root is the file tree`() {
        val root = currentPullRequestModel(open(emptySet())).filesRoot
        assertEquals(listOf("a.kt", "b.kt"), root.map { (it as FileNode.File).file.path })
    }

    @Test
    fun `three sections in order with their counts`() {
        val c = open(setOf("a.kt"), listOf(draft("a.kt", 1)), listOf(thread("1", "a.kt", 1, false), thread("2", "b.kt", 2, true)))
        assertEquals(
            listOf(ReviewNode.Header(Section.FILES, 2), ReviewNode.Header(Section.DRAFTS, 1), ReviewNode.Header(Section.CONVERSATIONS, 2)),
            reviewTree(c).map { it.node },
        )
    }

    @Test
    fun `files carry their viewed state inside compact folders`() {
        val folder = reviewTree(open(setOf("src/app/a.kt"), paths = listOf("src/app/a.kt", "src/app/b.kt")))[0].children.single()
        assertEquals(ReviewNode.Folder("src/app", "src/app"), folder.node)
        assertEquals(true, folder.expanded)
        assertEquals(listOf(true, false), folder.children.map { (it.node as ReviewNode.File).viewed })
    }

    @Test
    fun `active conversations start open, resolved ones closed, empty groups are left out`() {
        val both = reviewTree(open(emptySet(), threads = listOf(thread("1", "a.kt", 1, false), thread("2", "a.kt", 2, true))))[2]
        assertEquals(
            listOf(ReviewNode.Conversations(false, 1) to true, ReviewNode.Conversations(true, 1) to false),
            both.children.map { it.node to it.expanded },
        )
        assertEquals(listOf("thread:1"), both.children[0].children.map { it.node.key })
        val onlyActive = reviewTree(open(emptySet(), threads = listOf(thread("1", "a.kt", 1, false))))[2]
        assertEquals(listOf<ReviewNode>(ReviewNode.Conversations(false, 1)), onlyActive.children.map { it.node })
        assertEquals(emptyList<ReviewTreeItem>(), reviewTree(open(emptySet()))[2].children)
    }

    @Test
    fun `sections start open and their keys stay the same when counts change`() {
        val before = reviewTree(open(emptySet()))
        val after = reviewTree(open(setOf("a.kt"), listOf(draft("a.kt", 3))))
        assertEquals(listOf(true, true, true), before.map { it.expanded })
        assertEquals(before.map { it.node.key }, after.map { it.node.key })
        assertEquals("file:a.kt", before[0].children[0].node.key)
        assertEquals("draft:${after[1].children.single().let { (it.node as ReviewNode.DraftItem).draft.id }}", after[1].children.single().node.key)
    }

    @Test
    fun `guidance follows the review`() {
        assertEquals(message("guide.start"), guidance(open(emptySet())))
        assertEquals(message("guide.markViewed"), guidance(open(setOf("a.kt"))))
        assertEquals(message("guide.allViewed"), guidance(open(setOf("a.kt", "b.kt"))))
        // Drafts win, even with every file viewed: what is left is publishing them.
        assertEquals(message("guide.drafts", 2), guidance(open(setOf("a.kt", "b.kt"), listOf(draft("a.kt", 1), draft("b.kt", 2)))))
        assertEquals(message("guide.drafts", 1), guidance(open(emptySet(), listOf(draft("a.kt", 1)))))
    }

    @Test
    fun `the drafts guidance says one draft and several drafts differently`() {
        assertNotEquals(message("guide.drafts", 1).replace("1", "#"), message("guide.drafts", 2).replace("2", "#"))
    }
}
