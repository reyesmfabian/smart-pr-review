package com.fabianreyes.smartprreview.ui.diff

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.http.describeError
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.Thread
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.review.severityLabel
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Runs [work] off the EDT; [done] runs on the EDT on success, an error dialog on failure. */
internal fun runInBackground(project: Project, title: String, work: () -> Unit, onError: () -> Unit = {}, done: () -> Unit = {}) {
    object : Task.Backgroundable(project, title, false) {
        private var error: Throwable? = null
        override fun run(indicator: ProgressIndicator) {
            try {
                work()
            } catch (e: Exception) {
                error = e
            }
        }

        // Both run on the EDT.
        override fun onSuccess() {
            error?.let { onError(); Messages.showErrorDialog(project, describeError(it), title) } ?: done()
        }
    }.queue()
}

internal fun commentTextArea(text: String, editable: Boolean, rows: Int) = JBTextArea(text, rows, 50).apply {
    lineWrap = true
    wrapStyleWord = true
    isEditable = editable
}

/** Just below line [line0] of [editor]: where VS Code opens a comment. */
internal fun lineAnchor(editor: Editor, line0: Int): RelativePoint {
    val p = editor.logicalPositionToXY(LogicalPosition(line0, 0))
    return RelativePoint(editor.contentComponent, Point(p.x, p.y + editor.lineHeight))
}

/**
 * The popup a comment, a draft or the comment box lives in. It stays open on a click outside or a switch to another
 * application, as VS Code's comment widget does, so typed text is not lost; Esc and the Cancel button close it. A
 * dialog the IDE opens (Prepare Review, Confirm Exit) closes it: left open, it would float over the dialog and take
 * its focus.
 */
internal fun commentPopup(panel: JPanel, focus: JComponent): JBPopup =
    JBPopupFactory.getInstance().createComponentPopupBuilder(panel, focus)
        .setRequestFocus(true).setResizable(true).setMovable(true)
        .setCancelOnClickOutside(false).setCancelOnWindowDeactivation(false).setCancelOnOtherWindowOpen(true)
        .createPopup()

/** Shows [panel] in a popup below line [line0] of [editor], scrolled into view first; [onCreated] gets the popup. */
internal fun showAtLine(editor: Editor, line0: Int, panel: JPanel, focus: JComponent, onCreated: (JBPopup) -> Unit) {
    val popup = commentPopup(panel, focus)
    onCreated(popup)
    val line = line0.coerceIn(0, maxOf(0, editor.document.lineCount - 1))
    editor.scrollingModel.scrollTo(LogicalPosition(line, 0), ScrollType.MAKE_VISIBLE)
    editor.scrollingModel.runActionOnScrollingFinished {
        if (!editor.isDisposed && editor.contentComponent.isShowing) popup.show(lineAnchor(editor, line)) else popup.cancel()
    }
}

fun showThreadPopup(project: Project, session: ReviewSession, thread: Thread, editor: Editor, line0: Int) {
    val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8) }
    val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    for (c in thread.comments) {
        // Plain text components: comment bodies are untrusted and never rendered as HTML.
        list.add(JBLabel("${c.author} · ${c.createdAt}").apply { putClientProperty("html.disable", true) })
        list.add(commentTextArea(c.body, false, 3))
    }
    panel.add(JBScrollPane(list).apply { preferredSize = Dimension(520, 260) })
    val reply = commentTextArea("", true, 3)
    panel.add(JBScrollPane(reply))
    var popup: JBPopup? = null
    val replyButton = JButton(message("action.reply")).apply {
        addActionListener {
            val text = reply.text.trim()
            if (text.isEmpty()) return@addActionListener
            isEnabled = false
            runInBackground(project, message("action.reply"), { session.reply(thread.id, text) }, { isEnabled = true }) { popup?.cancel() }
        }
    }
    val resolveButton = JButton(message(if (thread.isResolved) "action.reopen" else "action.resolve")).apply {
        addActionListener {
            isEnabled = false
            runInBackground(project, text, { session.setResolved(thread.id, !thread.isResolved) }, { isEnabled = true }) { popup?.cancel() }
        }
    }
    val cancelButton = JButton(message("action.cancel")).apply { addActionListener { popup?.cancel() } }
    panel.add(JPanel().apply { add(replyButton); add(resolveButton); add(cancelButton) })
    showAtLine(editor, line0, panel, reply) { popup = it }
}

fun showDraftPopup(project: Project, session: ReviewSession, draft: Draft, editor: Editor, line0: Int) {
    val panel = JPanel(BorderLayout(0, 4)).apply { border = JBUI.Borders.empty(8) }
    val origin = draft.origin
    val label = if (origin is DraftOrigin.Ai) message("label.aiDraft", origin.profile, severityLabel(origin.severity)) else message("label.draft")
    panel.add(JBLabel(label).apply { putClientProperty("html.disable", true) }, BorderLayout.NORTH)
    val body = commentTextArea(draft.body, true, 6)
    panel.add(JBScrollPane(body).apply { preferredSize = Dimension(520, 160) }, BorderLayout.CENTER)
    var popup: JBPopup? = null
    val buttons = JPanel().apply {
        add(JButton(message("action.save")).apply {
            addActionListener {
                val text = body.text
                // A blank draft would publish as an empty comment: Delete is the way to drop one.
                if (text.isBlank()) return@addActionListener
                isEnabled = false
                runInBackground(project, message("action.save"), { session.updateDraft(draft.id, text) }, { isEnabled = true }) { popup?.cancel() }
            }
        })
        add(JButton(message("action.delete")).apply {
            addActionListener {
                isEnabled = false
                runInBackground(project, message("action.delete"), { session.removeDrafts(listOf(draft.id)) }, { isEnabled = true }) { popup?.cancel() }
            }
        })
        add(JButton(message("action.cancel")).apply { addActionListener { popup?.cancel() } })
    }
    panel.add(buttons, BorderLayout.SOUTH)
    showAtLine(editor, line0, panel, body) { popup = it }
}

/** Opens the popup for a thread or draft id, if it still exists, below line [line0]. */
internal fun showMarkPopup(project: Project, session: ReviewSession, id: String, editor: Editor, line0: Int) {
    val c = session.current ?: return
    c.threads.find { it.id == id }?.let { return showThreadPopup(project, session, it, editor, line0) }
    c.drafts.all().find { it.id == id }?.let { showDraftPopup(project, session, it, editor, line0) }
}
