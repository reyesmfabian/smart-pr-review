package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.PullRequest
import com.fabianreyes.smartprreview.providers.PullRequestSummary
import com.fabianreyes.smartprreview.providers.Side
import com.fabianreyes.smartprreview.providers.Thread
import com.fabianreyes.smartprreview.providers.ThreadComment
import com.fabianreyes.smartprreview.review.DraftStore
import com.fabianreyes.smartprreview.review.MemoryStore
import com.fabianreyes.smartprreview.review.OpenPullRequest
import com.fabianreyes.smartprreview.review.ReviewNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.CheckedTreeNode
import javax.swing.tree.TreePath

class ReviewTreeViewTest : BasePlatformTestCase() {
    private val sha = "b".repeat(40)
    private val store = DraftStore(MemoryStore(), "k")
    private val reported = mutableListOf<Pair<String, Boolean>>()

    private fun open(id: Int = 1, viewed: Set<String> = emptySet(), threads: List<Thread> = emptyList()): OpenPullRequest {
        val pr = PullRequest(PullRequestSummary(id, "t", "ana", "", "u"), "", false, sha, sha, emptyList(), null, "pr/$id", null, "n")
        val files = listOf("src/a.kt", "src/b.kt").map { ChangedFile(ChangedFile.Status.M, it) }
        return OpenPullRequest(pr, sha, files, threads, viewed.toMutableSet(), store, false)
    }

    private fun thread(id: String, resolved: Boolean) =
        Thread(id, "src/a.kt", Side.HEAD, 1, null, false, null, resolved, listOf(ThreadComment("c$id", "ana", "hi", "now")))

    private val threads = listOf(thread("1", false), thread("2", true))

    fun `test deleted files are red and additions green instead of grayed out`() {
        val removed = fileStatusAttributes(ChangedFile.Status.D).fgColor!!
        val added = fileStatusAttributes(ChangedFile.Status.A).fgColor!!
        assertTrue(removed.red > removed.green)
        assertTrue(added.green > added.red)
    }

    private fun view() = ReviewTreeView { file, viewed -> reported += file.path to viewed }
    private fun ReviewTreeView.find(key: String) = nodes().single { (it.userObject as ReviewNode).key == key }
    private fun ReviewTreeView.isOpen(key: String) = tree.isExpanded(TreePath(find(key).path))

    fun `test the first show opens files, drafts and active conversations, not resolved ones`() {
        val v = view()
        v.show(open(threads = threads))
        assertTrue(v.isOpen("section:FILES"))
        assertTrue(v.isOpen("folder:src"))
        assertTrue(v.isOpen("section:CONVERSATIONS"))
        assertTrue(v.isOpen("conversations:active"))
        assertFalse(v.isOpen("conversations:resolved"))
    }

    fun `test a rebuild keeps what the user expanded, collapsed and selected`() {
        val v = view()
        v.show(open(threads = threads))
        v.tree.collapsePath(TreePath(v.find("section:FILES").path))
        v.tree.expandPath(TreePath(v.find("conversations:resolved").path))
        v.tree.selectionPath = TreePath(v.find("thread:2").path)
        store.add(Draft("", "src/a.kt", 1, 1, null, null, "x", sha, DraftOrigin.Manual))
        v.show(open(threads = threads))
        assertFalse(v.isOpen("section:FILES"))
        assertTrue(v.isOpen("conversations:resolved"))
        // Drafts was empty before: its first draft opens it.
        assertTrue(v.isOpen("section:DRAFTS"))
        assertEquals("thread:2", v.selected()?.key)
    }

    fun `test another pull request starts from the defaults`() {
        val v = view()
        v.show(open(1))
        v.tree.collapsePath(TreePath(v.find("section:FILES").path))
        v.show(open(2))
        assertTrue(v.isOpen("section:FILES"))
    }

    fun `test checkboxes show the viewed files and a rebuild reports nothing`() {
        val v = view()
        v.show(open(viewed = setOf("src/a.kt")))
        v.show(open(viewed = setOf("src/b.kt")))
        assertFalse((v.find("file:src/a.kt") as CheckedTreeNode).isChecked)
        assertTrue((v.find("file:src/b.kt") as CheckedTreeNode).isChecked)
        assertEquals(emptyList<Pair<String, Boolean>>(), reported)
    }

    fun `test a rebuild keeps a section closed when the user closed it around the selection`() {
        val v = view()
        v.show(open())
        v.tree.selectionPath = TreePath(v.find("file:src/a.kt").path)
        v.tree.collapsePath(TreePath(v.find("section:FILES").path))
        v.show(open())
        assertFalse(v.isOpen("section:FILES"))
    }

    fun `test a rebuild keeps a group closed when its selected thread was hidden by closing it`() {
        val v = view()
        v.show(open(threads = threads))
        v.tree.expandPath(TreePath(v.find("conversations:resolved").path))
        v.tree.selectionPath = TreePath(v.find("thread:2").path)
        v.tree.collapsePath(TreePath(v.find("section:CONVERSATIONS").path))
        store.add(Draft("", "src/a.kt", 1, 1, null, null, "x", sha, DraftOrigin.Manual))
        v.show(open(threads = threads))
        assertFalse(v.isOpen("section:CONVERSATIONS"))
    }

    fun `test resolving the selected thread does not open the closed Resolved group`() {
        val v = view()
        v.show(open(threads = threads))
        v.tree.selectionPath = TreePath(v.find("thread:1").path)
        // Thread 1 is resolved (context menu): it moves into Resolved, which the user keeps closed.
        v.show(open(threads = listOf(thread("1", true), thread("2", true))))
        assertFalse(v.isOpen("conversations:resolved"))
    }

    fun `test a folder the user closed stays closed after a rebuild`() {
        val v = view()
        v.show(open())
        v.tree.collapsePath(TreePath(v.find("folder:src").path))
        v.show(open())
        assertFalse(v.isOpen("folder:src"))
        assertTrue(v.isOpen("section:FILES"))
    }
}
