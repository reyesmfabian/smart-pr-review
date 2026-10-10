package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.http.describeError
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.PublishResult
import com.fabianreyes.smartprreview.providers.Vote
import com.fabianreyes.smartprreview.review.DraftsChangedException
import com.fabianreyes.smartprreview.review.OpenPullRequest
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.review.allowedVotes
import com.fabianreyes.smartprreview.review.draftPlace
import com.fabianreyes.smartprreview.review.publishRevision
import com.fabianreyes.smartprreview.review.severityLabel
import com.fabianreyes.smartprreview.review.voteLabel
import com.fabianreyes.smartprreview.settings.SmartPrReviewSettings
import com.fabianreyes.smartprreview.ui.diff.openFileDiff
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.icons.AllIcons
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** What the dialog shows: the drafts by position, the stored summary, the votes and which one is selected. */
data class PublishModel(
    val drafts: List<Draft>,
    val summary: String,
    val votes: List<Vote>,
    val selected: Vote,
    /** [publishRevision] of what was shown: publishing refuses when the drafts changed since. */
    val revision: String,
)

fun publishModel(c: OpenPullRequest, supported: List<Vote>, allowApproval: Boolean, lastVote: Vote?): PublishModel {
    val votes = allowedVotes(supported, allowApproval)
    return PublishModel(
        drafts = c.drafts.all().sortedWith(compareBy<Draft, String>(String.CASE_INSENSITIVE_ORDER) { it.path }.thenBy { it.startLine }),
        summary = c.drafts.summary(),
        votes = votes,
        selected = lastVote?.takeIf { it in votes } ?: votes.first(),
        revision = publishRevision(c),
    )
}

/** Preview of the review (drafts, summary, vote) with the button that sends it. */
class PublishDialog(private val project: Project, private val session: ReviewSession) : DialogWrapper(project) {
    private val content = JPanel(BorderLayout())
    private var model: PublishModel? = null
    private var vote: Vote? = null
    private var publishing = false
    private var summaryText = ""

    init {
        title = message("publish.title", session.current?.pr?.id?.toString().orEmpty())
        setOKButtonText(message("publish.now"))
        init()
        session.onChange(disposable) {
            if (session.current == null) close(CANCEL_EXIT_CODE) else if (!publishing) render()
        }
        render()
    }

    override fun createCenterPanel(): JComponent = content.apply { preferredSize = Dimension(JBUI.scale(640), JBUI.scale(520)) }

    private fun render() {
        val c = session.current ?: return
        val m = publishModel(c, session.provider.supportedVotes, SmartPrReviewSettings.getInstance().allowApproval, vote)
        model = m
        summaryText = m.summary
        vote = m.selected

        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        fun add(component: JComponent) {
            component.alignmentX = 0f
            body.add(component)
        }

        val heading = "#${c.pr.id} ${c.pr.title}"
        val meta = JPanel(BorderLayout())
        meta.add(JBLabel(c.pr.author).apply { putClientProperty("html.disable", true); foreground = UIUtil.getContextHelpForeground() }, BorderLayout.CENTER)
        val url = browserUrl(c.pr.url)
        if (url != null) meta.add(ActionLink(message("action.openInBrowser")) { BrowserUtil.browse(url) }, BorderLayout.EAST)
        if (c.pr.body.isNotBlank()) {
            // The description is plain text in a collapsible panel titled with the pull request.
            val description = JBTextArea(c.pr.body).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
            val box = ScrollPaneFactory.createScrollPane(description).apply { preferredSize = Dimension(0, JBUI.scale(100)); isVisible = false }
            lateinit var toggle: ActionLink
            toggle = ActionLink(heading) {
                box.isVisible = !box.isVisible
                toggle.icon = if (box.isVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
                content.revalidate()
            }.apply { putClientProperty("html.disable", true); icon = AllIcons.General.ArrowRight }
            add(toggle)
            add(box)
        } else {
            add(JBLabel(heading).apply { putClientProperty("html.disable", true); font = font.deriveFont(java.awt.Font.BOLD) })
        }
        add(meta)
        add(JBLabel(message("publish.preview")).apply { border = JBUI.Borders.empty(8, 0) })

        add(JBLabel(message("publish.comments", m.drafts.size)).apply { font = font.deriveFont(java.awt.Font.BOLD) })
        if (m.drafts.isEmpty()) add(JBLabel(message("publish.noDrafts")).apply { foreground = UIUtil.getContextHelpForeground() })
        m.drafts.forEach { d -> add(draftRow(c, d)) }

        add(JBLabel(message("publish.decision")).apply { font = font.deriveFont(java.awt.Font.BOLD); border = JBUI.Borders.emptyTop(12) })
        add(JBLabel(message("publish.summary")))
        val summary = JBTextArea(m.summary, 5, 40).apply { lineWrap = true; wrapStyleWord = true }
        summary.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = changed()
            private fun changed() {
                summaryText = summary.text
                c.drafts.setSummary(summaryText)
            }
        })
        add(ScrollPaneFactory.createScrollPane(summary))
        add(JBLabel(message("publish.vote")).apply { border = JBUI.Borders.emptyTop(8) })
        val votes = ComboBox(DefaultComboBoxModel(m.votes.toTypedArray())).apply {
            selectedItem = m.selected
            renderer = object : com.intellij.ui.SimpleListCellRenderer<Vote>() {
                override fun customize(list: javax.swing.JList<out Vote>, value: Vote?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    text = value?.let { voteLabel(it) } ?: ""
                }
            }
            addActionListener { vote = selectedItem as? Vote }
        }
        add(votes)

        content.removeAll()
        content.add(ScrollPaneFactory.createScrollPane(body, true), BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    private fun draftRow(c: OpenPullRequest, d: Draft): JComponent {
        val origin = when (val o = d.origin) {
            DraftOrigin.Manual -> message("label.manual")
            is DraftOrigin.Ai -> "${severityLabel(o.severity)} · ${o.profile}"
        }
        val row = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(4, 0) }
        row.add(JBLabel("${draftPlace(d)}  ·  $origin").apply { putClientProperty("html.disable", true); foreground = UIUtil.getContextHelpForeground() }, BorderLayout.NORTH)
        row.add(JBTextArea(d.body).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }, BorderLayout.CENTER)
        val file = c.files.find { it.path == d.path }
        if (file != null) {
            row.add(JButton(message("action.openDiff")).apply { addActionListener { openFileDiff(project, session, file, d.startLine) } }, BorderLayout.EAST)
        }
        return row
    }

    override fun doOKAction() {
        val c = session.current ?: return
        val shown = model ?: return
        if (publishRevision(c) != shown.revision) {
            Messages.showInfoMessage(project, message("publish.changed"), title)
            render()
            return
        }
        val chosen = vote ?: return
        val text = summaryText
        publishing = true
        isOKActionEnabled = false
        // Captured on the EDT: a background thread's default modality would never run over this modal dialog.
        val modality = ModalityState.stateForComponent(content)
        // Not runWithRetry: its automatic Retry would run a second publish unseen; the user clicks Publish now again.
        object : Task.Backgroundable(project, message("publish.progress"), false) {
            private var result: PublishResult? = null

            override fun run(indicator: ProgressIndicator) {
                result = session.publish(text, chosen, shown.revision) { n ->
                    var ok = false
                    ApplicationManager.getApplication().invokeAndWait({
                        ok = Messages.showOkCancelDialog(
                            project, message("publish.staleQuestion", n.toString()), title,
                            message("publish.publish"), Messages.getCancelButton(), Messages.getWarningIcon(),
                        ) == Messages.OK
                    }, modality)
                    ok
                }
            }

            override fun onSuccess() = finished(result)

            override fun onThrowable(error: Throwable) {
                stopPublishing()
                if (error is DraftsChangedException) Messages.showInfoMessage(project, message("publish.changed"), title)
                else Messages.showErrorDialog(project, describeError(error), title)
            }
        }.queue()
    }

    /** A second publish of the same review must not start: closing is ignored while one is running. */
    override fun doCancelAction() {
        if (!publishing) super.doCancelAction()
    }

    private fun stopPublishing() {
        publishing = false
        isOKActionEnabled = true
        if (session.current != null && isShowing) render()
    }

    private fun finished(result: PublishResult?) {
        stopPublishing()
        when {
            result == null -> Unit
            result.failed.isNotEmpty() -> Messages.showErrorDialog(
                project, message("publish.partial", result.failed.size.toString(), result.failed.joinToString("; ") { it.error }), title,
            )
            else -> {
                NotificationGroupManager.getInstance().getNotificationGroup("Smart PR Review")
                    .createNotification(message("publish.done"), NotificationType.INFORMATION).notify(project)
                close(OK_EXIT_CODE)
            }
        }
    }
}
