package com.fabianreyes.smartprreview.ui.diff

import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.review.GutterMark
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.review.draftText
import com.fabianreyes.smartprreview.review.gutterMarks
import com.fabianreyes.smartprreview.review.threadText
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ui.update.UiNotifyConnector
import java.awt.Component
import javax.swing.Icon

/**
 * Review diffs show the pull request's threads and drafts as gutter icons and wrapped previews (a click on either opens
 * the popup), VS Code's "+" on the head side, and the popup of the comment the tree opened the diff for.
 */
class ReviewDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val info = request.getUserData(REVIEW_FILE) ?: return
        val viewer = viewer as? TwosideTextDiffViewer ?: return
        val project = context.project ?: return
        fun applyColors() {
            viewer.editor1.colorsScheme = reviewColorScheme(viewer.editor1.colorsScheme, Side.LEFT)
            viewer.editor2.colorsScheme = reviewColorScheme(viewer.editor2.colorsScheme, Side.RIGHT)
        }
        applyColors()
        ApplicationManager.getApplication().messageBus.connect(viewer).subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
            ApplicationManager.getApplication().invokeLater({ applyColors() }, Condition<Any?> { viewer.editor1.isDisposed || viewer.editor2.isDisposed })
        })
        val session = ReviewSession.getInstance(project)
        val added = mutableListOf<Pair<Editor, RangeHighlighter>>()
        val gutter = addCommentGutter(project, session, viewer, info)
        val previews = mapOf(Side.LEFT to CommentPreviews(viewer.editor1), Side.RIGHT to CommentPreviews(viewer.editor2))

        fun render() {
            for ((editor, h) in added) editor.markupModel.removeHighlighter(h)
            added.clear()
            gutter?.refresh()
            val c = session.current
            val marks = if (c == null) emptyList() else gutterMarks(c, info)
            for (mark in marks) {
                val editor = if (mark.side == Side.LEFT) viewer.editor1 else viewer.editor2
                val line = mark.line0.coerceIn(0, maxOf(0, editor.document.lineCount - 1))
                val h = editor.markupModel.addLineHighlighter(line, HighlighterLayer.SELECTION - 1, null)
                h.gutterIconRenderer = ReviewGutterIcon(mark, project, session, editor)
                added += editor to h
            }
            val threads = c?.threads.orEmpty().associateBy { it.id }
            val drafts = c?.drafts?.all().orEmpty().associateBy { it.id }
            for ((side, p) in previews) {
                p.show(marks.filter { it.side == side }.mapNotNull { m -> preview(m, threads, drafts)?.let { m.line0 to it } })
            }
        }

        // A click on a preview opens its thread or draft, as the gutter icon does.
        for (editor in listOf(viewer.editor1, viewer.editor2)) {
            editor.addEditorMouseListener(object : EditorMouseListener {
                override fun mouseClicked(e: EditorMouseEvent) {
                    val inlay = e.inlay ?: return
                    val preview = clickedPreview(e.mouseEvent.button, inlay.renderer) ?: return
                    showMarkPopup(project, session, preview.id, editor, editor.document.getLineNumber(inlay.offset))
                }
            }, viewer)
        }

        session.onChange(viewer, ::render)
        render()
        request.getUserData(REVIEW_FOCUS)?.let { focus(project, session, viewer, info, it) }
    }
}

/** VS Code's "+" on the head editor, unless the pull request deletes the file. */
private fun addCommentGutter(project: Project, session: ReviewSession, viewer: TwosideTextDiffViewer, info: ReviewDiffInfo): AddCommentGutter? {
    if (info.file.status == ChangedFile.Status.D) return null
    val editor = viewer.editor2
    // Only while the open pull request is the one this diff shows: a draft is written against the current head.
    val gutter = AddCommentGutter(editor, { session.current?.pr?.headSha == info.rightSha }) { range -> showCommentEditor(project, editor, info, range) }
    gutter.install(viewer)
    val c = session.current?.takeIf { it.pr.headSha == info.rightSha } ?: return gutter
    // commentableRanges runs git: off the EDT. Until it answers, and if it fails, there is no "+".
    ApplicationManager.getApplication().executeOnPooledThread {
        runCatching { session.commentableRanges(c, info.path) }.onSuccess { ranges ->
            ApplicationManager.getApplication().invokeLater({ gutter.setRanges(ranges) }, Condition<Any?> { editor.isDisposed })
        }
    }
    return gutter
}

/** The tree opened this diff for thread or draft [id]: its popup opens once its editor is on screen. */
private fun focus(project: Project, session: ReviewSession, viewer: TwosideTextDiffViewer, info: ReviewDiffInfo, id: String) {
    val c = session.current ?: return
    val mark = gutterMarks(c, info).find { it.id == id } ?: return
    val editor = if (mark.side == Side.LEFT) viewer.editor1 else viewer.editor2
    // Typed as Component: the JComponent overload of doWhenFirstShown is obsolete.
    val component: Component = editor.contentComponent
    UiNotifyConnector.doWhenFirstShown(component, { showMarkPopup(project, session, id, editor, mark.line0) }, viewer)
}

/** Gutter tooltips are rendered as HTML; comment and draft text is untrusted, so it is escaped. */
internal fun markTooltip(text: String?): String? = text?.let { StringUtil.escapeXmlEntities(it) }

private class ReviewGutterIcon(
    private val mark: GutterMark, private val project: Project, private val session: ReviewSession, private val editor: Editor,
) : GutterIconRenderer() {
    override fun getIcon(): Icon = markIcon(mark.kind)

    override fun getTooltipText(): String? {
        val c = session.current ?: return null
        return markTooltip(
            c.threads.find { it.id == mark.id }?.let { threadText(it) } ?: c.drafts.all().find { it.id == mark.id }?.let { draftText(it) },
        )
    }

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) = showMarkPopup(project, session, mark.id, editor, mark.line0)
    }

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun equals(other: Any?) = other is ReviewGutterIcon && other.mark.kind == mark.kind && other.mark.id == mark.id
    override fun hashCode() = 31 * mark.kind.hashCode() + mark.id.hashCode()
}
