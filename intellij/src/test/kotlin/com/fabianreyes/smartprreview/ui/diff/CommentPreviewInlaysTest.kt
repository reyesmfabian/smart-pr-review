package com.fabianreyes.smartprreview.ui.diff

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.Side
import com.fabianreyes.smartprreview.providers.Thread
import com.fabianreyes.smartprreview.providers.ThreadComment
import com.fabianreyes.smartprreview.review.GutterMark
import com.fabianreyes.smartprreview.review.MarkKind
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.MouseEvent
import java.awt.Dimension
import com.intellij.diff.util.Side as DiffSide

class CommentPreviewInlaysTest : BasePlatformTestCase() {
    private fun thread(body: String, replies: Int = 0, outdated: Boolean = false) = Thread(
        "t", "a.kt", Side.HEAD, 3, null, outdated, null, false,
        listOf(ThreadComment("1", "ana", body, "now")) + (1..replies).map { ThreadComment("r$it", "bo", "ok", "now") },
    )

    private fun draft(body: String, origin: DraftOrigin = DraftOrigin.Manual) = Draft("d", "a.kt", 3, 3, null, null, body, "h", origin)

    fun `test a thread preview is its author and text, with the reply count`() {
        assertEquals("ana: why this retry?", threadPreview(thread("why this retry?")))
        assertEquals("ana: why? · ${message("preview.replies", 3)}", threadPreview(thread("why?", replies = 3)))
        assertTrue(threadPreview(thread("x", outdated = true))!!.startsWith(message("label.outdated")))
        assertNull(threadPreview(thread("x").copy(comments = emptyList())))
    }

    fun `test previews flatten untrusted text to one plain line`() {
        assertEquals("ana: <b>bold</b> next line", threadPreview(thread("<b>bold</b>\n\n  next   line")))
        val long = draftPreview(draft("x".repeat(5000)))
        assertTrue(long.endsWith("…"))
        assertTrue(long.length < 200)
        assertFalse(long.contains('\n'))
    }

    fun `test draft previews name the AI profile and severity`() {
        assertEquals(message("preview.draft", "Use const"), draftPreview(draft("Use const")))
        val ai = draftPreview(draft("Leak", DraftOrigin.Ai("Security", "claude", DraftOrigin.Severity.ERROR)))
        assertEquals(message("preview.aiDraft", "Security", message("label.error"), "Leak"), ai)
    }

    fun `test the preview of a mark finds its thread or draft`() {
        val t = thread("hi")
        val d = draft("x")
        val threads = mapOf(t.id to t)
        val drafts = mapOf(d.id to d)
        assertEquals("ana: hi", preview(GutterMark(DiffSide.RIGHT, 2, MarkKind.THREAD_ACTIVE, "t"), threads, drafts)?.text)
        assertEquals(message("preview.draft", "x"), preview(GutterMark(DiffSide.RIGHT, 2, MarkKind.DRAFT_MANUAL, "d"), threads, drafts)?.text)
        assertNull(preview(GutterMark(DiffSide.RIGHT, 2, MarkKind.DRAFT_MANUAL, "gone"), threads, drafts))
    }

    private fun withEditor(test: (Editor) -> Unit) {
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument("a\nb\nc\nd"))
        try {
            test(editor)
        } finally {
            factory.releaseEditor(editor)
        }
    }

    private fun blocks(editor: Editor) =
        editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).map { it.renderer as CommentPreview }

    fun `test one preview per thread and draft, replaced on every change`() = withEditor { editor ->
        val previews = CommentPreviews(editor)
        val t = CommentPreview("t", "ana: hi", markIcon(MarkKind.THREAD_ACTIVE))
        previews.show(listOf(0 to t, 2 to CommentPreview("d", "Draft: x", markIcon(MarkKind.DRAFT_MANUAL))))
        assertEquals(listOf("t", "d"), blocks(editor).map { it.id })
        previews.show(listOf(0 to t)) // the draft was deleted
        assertEquals(listOf("t"), blocks(editor).map { it.id })
    }

    fun `test a preview past the end of the file goes on the last line`() = withEditor { editor ->
        CommentPreviews(editor).show(listOf(99 to CommentPreview("t", "x", markIcon(MarkKind.THREAD_ACTIVE))))
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        assertEquals(editor.document.lineCount - 1, editor.document.getLineNumber(inlay.offset))
    }

    fun `test long previews wrap vertically without widening the editor`() = withEditor { editor ->
        val viewport = (editor as EditorEx).scrollPane.viewport
        viewport.extentSize = Dimension(300, 200)
        val preview = CommentPreview("d", "Draft: " + "longword".repeat(100), markIcon(MarkKind.DRAFT_MANUAL))
        CommentPreviews(editor).show(listOf(0 to preview))
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        val narrowHeight = preview.calcHeightInPixels(inlay)
        assertTrue(preview.calcWidthInPixels(inlay) <= 300)
        assertTrue(editor.contentComponent.preferredSize.width <= 300)
        assertTrue(narrowHeight > editor.lineHeight)
        viewport.extentSize = Dimension(600, 200)
        inlay.update()
        assertTrue(preview.calcWidthInPixels(inlay) <= 600)
        assertTrue(preview.calcHeightInPixels(inlay) < narrowHeight)
    }

    fun `test only a left click on a preview opens it`() {
        val p = CommentPreview("t", "x", markIcon(MarkKind.THREAD_ACTIVE))
        assertSame(p, clickedPreview(MouseEvent.BUTTON1, p))
        assertNull(clickedPreview(MouseEvent.BUTTON3, p)) // the right click is the diff's context menu
        assertNull(clickedPreview(MouseEvent.BUTTON1, Any()))
    }
}
