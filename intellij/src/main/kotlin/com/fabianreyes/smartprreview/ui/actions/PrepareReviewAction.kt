package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.ui.PublishDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Opens the publish dialog for the open pull request. Registered in plugin.xml, so the project comes from the event. */
class PrepareReviewAction : DumbAwareAction(message("action.prepareReview"), null, AllIcons.Actions.Commit) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { ReviewSession.getInstance(it).current } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PublishDialog(project, ReviewSession.getInstance(project)).show()
    }
}
