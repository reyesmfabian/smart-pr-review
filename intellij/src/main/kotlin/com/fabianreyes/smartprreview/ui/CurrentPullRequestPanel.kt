package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.review.ReviewNode
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.review.currentPullRequestModel
import com.fabianreyes.smartprreview.review.guidance
import com.fabianreyes.smartprreview.ui.diff.openDraft
import com.fabianreyes.smartprreview.ui.diff.openFileDiff
import com.fabianreyes.smartprreview.ui.diff.openThread
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.tree.DefaultMutableTreeNode

/** The guidance line: wrapped, read-only prose under the header. */
internal fun guideArea(): JBTextArea = object : JBTextArea() {
    // A text area's theme font is monospaced; set again on every theme change, which runs updateUI.
    override fun updateUI() {
        super.updateUI()
        font = JBUI.Fonts.label()
    }
}.apply {
    isEditable = false
    isOpaque = false
    lineWrap = true
    wrapStyleWord = true
    border = JBUI.Borders.empty()
    foreground = UIUtil.getContextHelpForeground()
}

/** A row of header controls that wraps when the tool window is narrow; FlowLayout would hide what does not fit. */
internal fun wrappingRow(vararg items: JComponent): JPanel = JPanel(WrapLayout(FlowLayout.LEFT, 0, 0)).apply { items.forEach { add(it) } }

/**
 * The open pull request, as VS Code's "Current Pull Request" view: a header (title and author, progress, Review with AI,
 * Prepare Review, Checkout · Open in Browser · Exit, the next step) above one tree. One click opens.
 */
class CurrentPullRequestPanel(private val project: Project, private val session: ReviewSession) : JPanel(BorderLayout()), Disposable {
    private val title = SimpleColoredComponent()
    private val progressText = JBLabel()
    private val progress = JProgressBar()
    private val prepare = JButton()
    private val checkout = JButton(message("action.checkout")).apply { addActionListener { runAction("SmartPrReview.Checkout") } }
    private val links = wrappingRow()
    private val guide = guideArea()
    private val view = ReviewTreeView { file, viewed -> setViewed(file.path, viewed) }

    init {
        val header = JPanel(VerticalLayout(JBUI.scale(4))).apply { border = JBUI.Borders.empty(6, 8) }
        header.add(title)
        header.add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(progressText, BorderLayout.WEST)
            add(progress, BorderLayout.CENTER)
        })
        header.add(
            wrappingRow(
                JButton(message("ai.reviewWithAi")).apply { addActionListener { runAction("SmartPrReview.ReviewWithAi") } },
                prepare.apply { addActionListener { runAction("SmartPrReview.PrepareReview") } },
            ),
        )
        header.add(wrappingRow(
            checkout,
            JButton(message("action.openReviewWindow")).apply { addActionListener { runAction("SmartPrReview.OpenReviewWindow") } },
        ))
        header.add(wrappingRow(
            JButton(message("worktree.create")).apply { addActionListener { runAction("SmartPrReview.CreateWorktree") } },
            JButton(message("worktree.manage")).apply { addActionListener { runAction("SmartPrReview.ManageWorktrees") } },
        ))
        header.add(links)
        header.add(guide)
        add(header, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(view.tree), BorderLayout.CENTER)

        installSingleClickOpen(view.tree) { path, pinned -> open((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject, pinned) }
        PopupHandler.installPopupMenu(view.tree, DefaultActionGroup(menu()), "SmartPrReviewCurrentPullRequestPopup")

        session.onChange(this) { rebuild() }
        rebuild()
    }

    override fun dispose() {}

    /** Runs a registered action from this panel, which gives it the project; a disabled action does nothing. */
    private fun runAction(id: String) {
        val action = ActionManager.getInstance().getAction(id) ?: return
        ActionManager.getInstance().tryToExecute(action, null, this, ActionPlaces.TOOLWINDOW_CONTENT, true)
    }

    private fun open(node: Any?, pinned: Boolean): Boolean {
        when (node) {
            is ReviewNode.File -> openFileDiff(project, session, node.file, pinned = pinned)
            // A draft or a conversation opens once: the second click of a double click adds nothing.
            is ReviewNode.DraftItem -> if (!pinned) openDraft(project, session, node.draft)
            is ReviewNode.ThreadItem -> if (!pinned) openThread(project, session, node.thread)
            else -> return false
        }
        return true
    }

    private fun setViewed(path: String, viewed: Boolean) =
        // Whatever happens, the checkbox goes back to the session's real state.
        runWithRetry(project, message("action.markViewed"), { session.setViewed(path, viewed) }, onFailure = ::rebuild)

    private fun resolve(resolved: Boolean) {
        val thread = (view.selected() as? ReviewNode.ThreadItem)?.thread ?: return
        runWithRetry(project, message(if (resolved) "action.resolve" else "action.reopen"), { session.setResolved(thread.id, resolved) })
    }

    private fun menu(): List<AnAction> {
        fun node() = view.selected()
        return listOf(
            item(message("action.openDiff"), { node() is ReviewNode.File }) { open(node(), false) },
            item(message("action.markViewed"), { (node() as? ReviewNode.File)?.viewed == false }) {
                (node() as? ReviewNode.File)?.let { setViewed(it.file.path, true) }
            },
            item(message("action.markNotViewed"), { (node() as? ReviewNode.File)?.viewed == true }) {
                (node() as? ReviewNode.File)?.let { setViewed(it.file.path, false) }
            },
            item(message("action.edit"), { node() is ReviewNode.DraftItem }) { open(node(), false) },
            item(message("action.delete"), { node() is ReviewNode.DraftItem }) {
                (node() as? ReviewNode.DraftItem)?.let { d -> runWithRetry(project, message("action.delete"), { session.removeDrafts(listOf(d.draft.id)) }) }
            },
            item(message("action.reply"), { node() is ReviewNode.ThreadItem }) { open(node(), false) },
            item(message("action.resolve"), { (node() as? ReviewNode.ThreadItem)?.thread?.isResolved == false }) { resolve(true) },
            item(message("action.reopen"), { (node() as? ReviewNode.ThreadItem)?.thread?.isResolved == true }) { resolve(false) },
        )
    }

    private fun item(text: String, visible: () -> Boolean, run: () -> Unit): AnAction = object : DumbAwareAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = visible()
        }

        override fun actionPerformed(e: AnActionEvent) = run()
    }

    private fun rebuild() {
        val c = session.current ?: return
        val m = currentPullRequestModel(c)
        title.clear()
        title.append("#${c.pr.id} ${c.pr.title}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        title.append("  ·  ${c.pr.author}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        progressText.text = m.progressText
        progress.maximum = maxOf(1, c.files.size)
        progress.value = m.viewedCount
        prepare.text = message("header.prepareReview", m.drafts.size)
        links.removeAll()
        checkout.isEnabled = !c.checkedOut
        checkout.text = message(if (c.checkedOut) "checkout.done" else "action.checkout")
        links.add(ActionLink(message("link.openInBrowser")) { runAction("SmartPrReview.OpenInBrowser") })
        links.add(JBLabel("  ·  "))
        links.add(ActionLink(message("link.exit")) { runAction("SmartPrReview.ExitReview") })
        guide.text = guidance(c)
        view.show(c)
        revalidate()
        repaint()
    }
}
