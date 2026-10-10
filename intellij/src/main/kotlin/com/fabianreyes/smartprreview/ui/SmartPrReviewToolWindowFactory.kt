package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.SmartPrReviewRepository
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.ai.cleanupWorktrees
import com.fabianreyes.smartprreview.ai.worktreesDir
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** The ⋮ menu: only what is rare; Refresh and Search are on the title bar, the rest on the Current Pull Request header. */
internal fun gearActions(actions: ActionManager): ActionGroup =
    DefaultActionGroup(listOf("SmartPrReview.DiscardDrafts", "SmartPrReview.CreateProfile", "SmartPrReview.ManageWorktrees").mapNotNull { actions.getAction(it) })

class SmartPrReviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RepositoryStatePanel(project)
        Disposer.register(toolWindow.disposable, panel)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, message("toolWindow.pullRequests"), false))
        val repository = SmartPrReviewRepository.getInstance(project)
        val session = ReviewSession.getInstance(project)
        // The session follows the state: attached while Ready, closed otherwise.
        fun show(state: RepositoryState) {
            if (state is RepositoryState.Ready) {
                session.attach(state)
                // Sandboxes an IDE that closed mid-review left behind.
                ApplicationManager.getApplication().executeOnPooledThread {
                    runCatching { cleanupWorktrees(state.git, worktreesDir(), 24 * 60 * 60 * 1000L) }
                }
            } else session.close()
            panel.show(state)
        }
        repository.onChange(toolWindow.disposable) { show(it) }
        repository.refreshWhenTrusted(toolWindow.disposable)
        show(repository.state)
        val actions = ActionManager.getInstance()
        // As VS Code's view title: Refresh and Search on the bar, everything else under ⋮.
        toolWindow.setTitleActions(listOf("SmartPrReview.Refresh", "SmartPrReview.Search").mapNotNull { actions.getAction(it) })
        toolWindow.setAdditionalGearActions(gearActions(actions))
        repository.refresh()
    }
}
