package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.FakeProvider
import com.fabianreyes.smartprreview.providers.Side
import com.fabianreyes.smartprreview.providers.Thread
import com.fabianreyes.smartprreview.providers.ThreadComment
import com.fabianreyes.smartprreview.ui.diff.ReviewDiffInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.diff.util.Side as DiffSide

class GutterMarksTest : BasePlatformTestCase() {
    private val mergeBase = "m".repeat(40)
    private val head = "b".repeat(40)
    private val old = "c".repeat(40)

    private fun thread(
        id: String, side: Side = Side.HEAD, line: Int? = 5, outdated: Boolean = false,
        original: Thread.Original? = null, resolved: Boolean = false, path: String = "a.kt", body: String = "x".repeat(100),
    ) = Thread(id, path, side, line, null, outdated, original, resolved, listOf(ThreadComment("1", "ann", body, "now")))

    private fun pr(threads: List<Thread>, drafts: List<Draft> = emptyList(), vararg files: ChangedFile): OpenPullRequest {
        val p = FakeProvider().apply { headSha = head }.getPullRequest(1)
        val store = DraftStore(MemoryStore(), "k")
        drafts.forEach { store.add(it) }
        return OpenPullRequest(p, mergeBase, files.toList(), threads, mutableSetOf(), store, false)
    }

    private fun draft(line: Int, origin: DraftOrigin = DraftOrigin.Manual, path: String = "a.kt") =
        Draft("", path, line, line, null, null, "body", head, origin)

    private val info = ReviewDiffInfo("a.kt", mergeBase, head, ChangedFile(ChangedFile.Status.M, "a.kt"))

    fun `test live head threads go right, base threads left, sorted`() {
        val c = pr(listOf(thread("h", line = 9), thread("b", Side.BASE, 3, resolved = true), thread("h2", line = 2)))
        assertEquals(
            listOf(
                GutterMark(DiffSide.LEFT, 2, MarkKind.THREAD_RESOLVED, "b"),
                GutterMark(DiffSide.RIGHT, 1, MarkKind.THREAD_ACTIVE, "h2"),
                GutterMark(DiffSide.RIGHT, 8, MarkKind.THREAD_ACTIVE, "h"),
            ),
            gutterMarks(c, info),
        )
    }

    fun `test base thread on a renamed file shows left only when leftSha is the merge base`() {
        val file = ChangedFile(ChangedFile.Status.R, "new.kt", "old.kt")
        val c = pr(listOf(thread("b", Side.BASE, 3, path = "new.kt")), emptyList(), file)
        val renamed = ReviewDiffInfo("new.kt", mergeBase, head, file)
        assertEquals(listOf(GutterMark(DiffSide.LEFT, 2, MarkKind.THREAD_ACTIVE, "b")), gutterMarks(c, renamed))
        assertEquals(emptyList<GutterMark>(), gutterMarks(c, renamed.copy(leftSha = old)))
    }

    fun `test outdated thread shows left only when the viewer left sha is its original commit`() {
        val t = thread("o", line = null, outdated = true, original = Thread.Original(old, 7))
        val c = pr(listOf(t))
        assertEquals(emptyList<GutterMark>(), gutterMarks(c, info))
        assertEquals(listOf(GutterMark(DiffSide.LEFT, 6, MarkKind.THREAD_ACTIVE, "o")), gutterMarks(c, info.copy(leftSha = old)))
    }

    fun `test threads of other files and without lines are skipped`() {
        val c = pr(listOf(thread("x", path = "b.kt"), thread("y", line = null)))
        assertEquals(emptyList<GutterMark>(), gutterMarks(c, info))
    }

    fun `test drafts go right only when the viewer shows the head`() {
        val ai = DraftOrigin.Ai("p", "e", DraftOrigin.Severity.INFO)
        val c = pr(emptyList(), listOf(draft(4), draft(2, ai), draft(1, path = "b.kt")))
        val marks = gutterMarks(c, info)
        assertEquals(listOf(1 to MarkKind.DRAFT_AI, 3 to MarkKind.DRAFT_MANUAL), marks.map { it.line0 to it.kind })
        assertTrue(marks.all { it.side == DiffSide.RIGHT })
        assertEquals(emptyList<GutterMark>(), gutterMarks(c, info.copy(rightSha = old)))
    }

    fun `test texts`() {
        val t = thread("a")
        assertEquals("ann: " + "x".repeat(80), threadText(t))
        assertTrue(threadText(t.copy(isOutdated = true)).endsWith(" · ann: " + "x".repeat(80)))
        assertEquals("", threadText(t.copy(comments = emptyList())))
        assertEquals("hi", draftText(draft(1).copy(body = "\n  \nhi\nthere")))
        assertEquals("Warning", severityLabel(DraftOrigin.Severity.WARNING))
    }

    fun `test selection rule`() {
        val len = { l: Int -> listOf(10, 4, 7)[l] }
        assertEquals(SelectedRange(0, 0, null, null), selectedRange(0, 3, 0, 3, len))
        assertEquals(SelectedRange(0, 0, 3, 6), selectedRange(0, 2, 0, 5, len))
        assertEquals(SelectedRange(0, 1, 1, 5), selectedRange(0, 0, 2, 0, len))
        assertEquals(SelectedRange(1, 1, null, null), selectedRange(1, 0, 1, 0, len))
        assertEquals(SelectedRange(0, 2, 1, 3), selectedRange(0, 0, 2, 2, len))
    }
}
