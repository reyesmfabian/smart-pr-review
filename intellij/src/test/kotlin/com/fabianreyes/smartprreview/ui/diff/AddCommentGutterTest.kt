package com.fabianreyes.smartprreview.ui.diff

import com.fabianreyes.smartprreview.git.LineRange
import com.fabianreyes.smartprreview.review.SelectedRange
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AddCommentGutterTest : BasePlatformTestCase() {
    /** 1-based lines 2 and 3, that is 0-based lines 1 and 2. */
    private val ranges = listOf(LineRange(2, 3))

    fun `test the plus needs the head side and a commentable line`() {
        assertNull(addCommentTarget(false, 1, null, null, 10))
        assertEquals(SelectedRange(1, 1, null, null), addCommentTarget(true, 1, null, ranges, 10))
        assertNull(addCommentTarget(true, 0, null, ranges, 10))
        assertNull(addCommentTarget(true, 3, null, ranges, 10))
        // A deleted file has no commentable lines.
        assertNull(addCommentTarget(true, 1, null, emptyList(), 10))
        // Azure DevOps: every line.
        assertEquals(SelectedRange(7, 7, null, null), addCommentTarget(true, 7, null, null, 10))
        assertNull(addCommentTarget(true, null, null, null, 10))
    }

    fun `test a line past the end of the file gets no plus`() {
        assertNull(addCommentTarget(true, 10, null, null, 10))
    }

    fun `test a multi-line selection puts the plus on its last line and comments the range`() {
        val sel = SelectedRange(1, 2, 3, 5)
        assertEquals(sel, addCommentTarget(true, 0, sel, ranges, 10))
        assertEquals(sel, addCommentTarget(true, null, sel, ranges, 10))
        assertNull(addCommentTarget(true, null, SelectedRange(1, 4, 1, 2), ranges, 10))
    }

    fun `test a selection inside the hovered line keeps its columns`() {
        val sel = SelectedRange(1, 1, 3, 7)
        assertEquals(sel, addCommentTarget(true, 1, sel, ranges, 10))
        assertEquals(SelectedRange(2, 2, null, null), addCommentTarget(true, 2, sel, ranges, 10))
    }

    private fun withEditor(test: (Editor, Disposable) -> Unit) {
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument("a\nb\nc\nd\ne"))
        val scope = Disposer.newDisposable("gutter")
        try {
            test(editor, scope)
        } finally {
            Disposer.dispose(scope)
            factory.releaseEditor(editor)
        }
    }

    private fun plus(editor: Editor) =
        editor.markupModel.allHighlighters.filter { it.gutterIconRenderer is AddCommentIcon }.map { editor.document.getLineNumber(it.startOffset) }

    fun `test in an editor the plus follows the mouse over commentable lines only`() = withEditor { editor, scope ->
        val gutter = AddCommentGutter(editor, { true }) {}
        gutter.install(scope)
        gutter.hover(1)
        assertEquals(emptyList<Int>(), plus(editor)) // git has not answered yet
        gutter.setRanges(ranges)
        assertEquals(listOf(1), plus(editor))
        gutter.hover(4)
        assertEquals(emptyList<Int>(), plus(editor))
        gutter.hover(2)
        assertEquals(listOf(2), plus(editor))
        gutter.hover(null)
        assertEquals(emptyList<Int>(), plus(editor))
    }

    fun `test in an editor a stale diff loses its plus`() = withEditor { editor, _ ->
        var head = true
        val gutter = AddCommentGutter(editor, { head }) {}
        gutter.setRanges(null)
        gutter.hover(0)
        assertEquals(listOf(0), plus(editor))
        head = false // another pull request, or new commits, since this diff opened
        gutter.refresh()
        assertEquals(emptyList<Int>(), plus(editor))
    }

    fun `test in an editor clicking the plus hands over the selected lines`() = withEditor { editor, scope ->
        val got = mutableListOf<SelectedRange>()
        val gutter = AddCommentGutter(editor, { true }) { got += it }
        gutter.install(scope)
        gutter.setRanges(null)
        val doc = editor.document
        editor.selectionModel.setSelection(doc.getLineStartOffset(1), doc.getLineEndOffset(3))
        assertEquals(listOf(3), plus(editor))
        val icon = editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer as? AddCommentIcon }.single()
        icon.clickAction!!.actionPerformed(TestActionEvent.createTestEvent())
        assertEquals(listOf(SelectedRange(1, 3, 1, 2)), got)
    }
}
