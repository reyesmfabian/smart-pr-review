package com.fabianreyes.smartprreview.ui.diff

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.review.SelectedRange
import com.fabianreyes.smartprreview.review.suggestionBody
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.TextRange
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The box VS Code's "+" opens below the last line of [range] (0-based lines; columns as drafts store them): a plain-text
 * comment with Add Draft, which keeps the selected columns, and Add as Suggestion, which puts the lines' code in a
 * suggestion block after the comment.
 */
fun showCommentEditor(project: Project, editor: Editor, info: ReviewDiffInfo, range: SelectedRange) {
    val start = range.startLine + 1
    val end = range.endLine + 1
    val doc = editor.document
    val code = doc.getText(TextRange(doc.getLineStartOffset(range.startLine), doc.getLineEndOffset(range.endLine)))
    val panel = JPanel(BorderLayout(0, JBUI.scale(4))).apply { border = JBUI.Borders.empty(8) }
    val place = if (start == end) "${info.path}:$start" else "${info.path}:$start-$end"
    panel.add(JBLabel(place).apply { putClientProperty("html.disable", true) }, BorderLayout.NORTH)
    val body = commentTextArea("", true, 4).apply {
        emptyText.text = message("draft.bodyPrompt")
    }
    panel.add(JBScrollPane(body), BorderLayout.CENTER)
    var popup: JBPopup? = null
    val addDraft = JButton(message("comment.addDraft"))
    val addSuggestion = JButton(message("comment.addAsSuggestion"))
    val buttons = listOf(addDraft, addSuggestion)

    fun submit(title: String, hint: String, text: String, columns: Boolean) {
        buttons.forEach { it.isEnabled = false }
        addDraftInDiff(project, info, title, hint, start, end, { session, c ->
            session.addDraftTo(c, info.path, start, end, text, DraftOrigin.Manual, range.startColumn.takeIf { columns }, range.endColumn.takeIf { columns })
        }, done = { popup?.cancel() }, failed = { buttons.forEach { it.isEnabled = true } })
    }

    addDraft.addActionListener {
        // A blank draft would publish as an empty comment.
        val text = body.text
        if (text.isNotBlank()) submit(message("comment.addDraft"), message("draft.commentHint"), text, columns = true)
    }
    addSuggestion.addActionListener {
        submit(message("comment.addAsSuggestion"), message("draft.suggestionHint"), suggestionBody(body.text, code), columns = false)
    }
    panel.add(JPanel().apply {
        buttons.forEach { add(it) }
        add(JButton(message("action.cancel")).apply { addActionListener { popup?.cancel() } })
    }, BorderLayout.SOUTH)
    showAtLine(editor, range.endLine, panel, body) { popup = it }
}
