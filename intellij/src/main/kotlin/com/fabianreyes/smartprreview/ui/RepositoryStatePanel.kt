package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.SmartPrReviewRepository
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.fabianreyes.smartprreview.review.ReviewSession
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel

/** Welcome text of a state and, when the state has one, the label of the action that leaves it. */
fun welcome(state: RepositoryState): Pair<String, String?> = when (state) {
    RepositoryState.Untrusted -> message("welcome.untrusted") to null
    RepositoryState.NoRepository -> message("welcome.noRepository") to null
    RepositoryState.Unsupported -> message("welcome.unsupported") to null
    is RepositoryState.SignedOut ->
        if (state.remote.kind == ProviderKind.GITHUB) message("welcome.signedOutGitHub") to message("action.signIn")
        else message("welcome.signedOutAzureDevOps") to message("action.enterPat")
    is RepositoryState.Ready -> message("welcome.ready", "${state.remote.owner}/${state.remote.repo}") to null
}

/**
 * The tool window's content: the state as native "empty text" or, once Ready, VS Code's two sections: Pull Requests
 * above Current Pull Request, split by a draggable divider whose position is remembered.
 */
class RepositoryStatePanel(
    private val project: Project,
    /** Seam for tests: opens a pull request and calls back once it opened or failed. */
    private val openPr: (Int, () -> Unit) -> Unit = { id, finished -> openPullRequest(project, id, finished) },
) : JBPanelWithEmptyText(BorderLayout()), Disposable {
    private val session = ReviewSession.getInstance(project)
    private var listScope: Disposable? = null

    /** The lower section while Ready: its empty text, or the open pull request. */
    private var current: JBPanelWithEmptyText? = null
    private var review: CurrentPullRequestPanel? = null

    /** The lower section's progress while a pull request opens, as VS Code shows it inside the view. */
    private var loading: JBLoadingPanel? = null

    init {
        // A review opens or closes: only the lower section changes.
        session.onChange(this) {
            if (current != null && (session.current != null) != (review != null)) showReview()
        }
    }

    fun show(state: RepositoryState) {
        disposeReview()
        listScope?.let(Disposer::dispose)
        listScope = null
        current = null
        loading = null
        removeAll()
        if (state is RepositoryState.Ready) {
            emptyText.clear()
            val scope = Disposer.newDisposable("pullRequestList").also { Disposer.register(this, it) }
            listScope = scope
            val list = PullRequestTreePanel(project, state, onOpen = ::openFromList)
            SmartPrReviewRepository.getInstance(project).onListRefresh(scope) { list.refresh() }
            val lower = JBPanelWithEmptyText(BorderLayout()).withEmptyText(message("pr.select"))
            current = lower
            val progress = JBLoadingPanel(BorderLayout(), scope).apply { contentPanel.add(lower, BorderLayout.CENTER) }
            loading = progress
            add(OnePixelSplitter(true, "SmartPrReview.sections", 0.4f).apply {
                firstComponent = section(message("toolWindow.pullRequests"), list)
                secondComponent = section(message("toolWindow.currentPullRequest"), progress)
            }, BorderLayout.CENTER)
            showReview()
            return
        }
        revalidate()
        repaint()
        val (text, action) = welcome(state)
        emptyText.clear().appendText(text)
        if (state is RepositoryState.SignedOut && action != null) {
            emptyText.appendLine(action, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                state.auth.signIn(force = false) // EDT: the link click
                SmartPrReviewRepository.getInstance(project).refresh()
            }
        }
    }

    /** Opens a pull request clicked in the list, with the lower section showing the progress until it opened or failed. */
    internal fun openFromList(id: Int) {
        val progress = loading ?: return
        progress.startLoading()
        openPr(id) { progress.stopLoading() }
    }

    private fun showReview() {
        val lower = current ?: return
        disposeReview()
        lower.removeAll()
        if (session.current != null) {
            val panel = CurrentPullRequestPanel(project, session)
            Disposer.register(this, panel)
            review = panel
            lower.add(panel, BorderLayout.CENTER)
        }
        lower.revalidate()
        lower.repaint()
    }

    private fun disposeReview() {
        review?.let(Disposer::dispose)
        review = null
    }

    override fun dispose() = disposeReview()
}

/** A section titled as VS Code does: its name in small capitals above [content]. */
private fun section(title: String, content: JComponent): JPanel = JPanel(BorderLayout()).apply {
    add(JBLabel(title.uppercase(), UIUtil.ComponentStyle.SMALL).apply {
        font = font.deriveFont(Font.BOLD)
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(4, 8)
    }, BorderLayout.NORTH)
    add(content, BorderLayout.CENTER)
}
