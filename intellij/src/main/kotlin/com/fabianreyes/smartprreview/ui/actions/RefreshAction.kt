package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.SmartPrReviewRepository
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.ui.runWithRetry
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Reloads the pull request list and, with a pull request open, its data; tells when it has new commits. */
class RefreshAction : DumbAwareAction(message("action.refresh"), null, AllIcons.Actions.Refresh) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SmartPrReviewRepository.getInstance(project).refreshList()
        val session = ReviewSession.getInstance(project)
        val before = session.current?.pr ?: return
        runWithRetry(project, message("action.refresh"), { session.refresh() }, done = {
            val now = session.current?.pr
            if (now?.id == before.id && now.headSha != before.headSha) {
                NotificationGroupManager.getInstance().getNotificationGroup("Smart PR Review")
                    .createNotification(message("pr.newCommits"), NotificationType.INFORMATION).notify(project)
            }
        })
    }
}
