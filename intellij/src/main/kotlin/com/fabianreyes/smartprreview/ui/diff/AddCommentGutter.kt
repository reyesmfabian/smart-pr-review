package com.fabianreyes.smartprreview.ui.diff

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.git.LineRange
import com.fabianreyes.smartprreview.review.SelectedRange
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.Icon
import javax.swing.SwingUtilities

/**
 * The lines VS Code's "+" comments (0-based), or null when it is hidden. Only on the head side of the open pull
 * request's diff ([head]); on a selection of several lines it goes to the selection's last line, otherwise to the
 * hovered line (keeping the columns of a selection inside that line); never past the document's [lines]; and only
 * when those lines fall inside one commentable range (null [ranges]: every line).
 */
fun addCommentTarget(head: Boolean, hover: Int?, selection: SelectedRange?, ranges: List<LineRange>?, lines: Int): SelectedRange? {
    if (!head) return null
    val target = when {
        selection != null && selection.endLine > selection.startLine -> selection
        hover == null || hover >= lines -> return null
        selection != null && selection.startLine == hover -> selection
        else -> SelectedRange(hover, hover, null, null)
    }
    return target.takeIf { lineAllowed(ranges, it.startLine + 1, it.endLine + 1) }
}

/** The "+" that follows the mouse over a review diff's head editor; a click hands its lines to [onAdd]. EDT only. */
class AddCommentGutter(private val editor: Editor, private val head: () -> Boolean, private val onAdd: (SelectedRange) -> Unit) {
    private var loaded = false
    private var ranges: List<LineRange>? = null
    private var hover: Int? = null
    private var highlighter: RangeHighlighter? = null
    private var target: SelectedRange? = null

    fun install(parent: Disposable) {
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) = hover(e.logicalPosition.line)
        }, parent)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseExited(e: EditorMouseEvent) {
                // Moving from the text to the gutter, to click the "+", does not leave the editor.
                val p = SwingUtilities.convertPoint(e.mouseEvent.component, e.mouseEvent.point, editor.component)
                if (!editor.component.contains(p)) hover(null)
            }
        }, parent)
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = refresh()
        }, parent)
    }

    /** The commentable ranges once git answered (null: every line); until then there is no "+". */
    fun setRanges(ranges: List<LineRange>?) {
        this.ranges = ranges
        loaded = true
        refresh()
    }

    internal fun hover(line: Int?) {
        hover = line
        refresh()
    }

    fun refresh() {
        val selection = if (editor.selectionModel.hasSelection()) selection(editor) else null
        val next = if (loaded && !editor.isDisposed) addCommentTarget(head(), hover, selection, ranges, editor.document.lineCount) else null
        if (next == target) return
        highlighter?.let { editor.markupModel.removeHighlighter(it) }
        highlighter = next?.let { t ->
            editor.markupModel.addLineHighlighter(t.endLine, HighlighterLayer.SELECTION - 1, null).apply { gutterIconRenderer = AddCommentIcon(t, onAdd) }
        }
        target = next
    }
}

internal class AddCommentIcon(private val target: SelectedRange, private val onAdd: (SelectedRange) -> Unit) : GutterIconRenderer() {
    override fun getIcon(): Icon = AllIcons.General.Add

    override fun getTooltipText(): String = message("comment.addHere")

    override fun isNavigateAction() = true

    override fun getClickAction(): AnAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) = onAdd(target)
    }

    override fun getAlignment(): Alignment = Alignment.RIGHT

    override fun equals(other: Any?) = other is AddCommentIcon && other.target == target
    override fun hashCode() = target.hashCode()
}
