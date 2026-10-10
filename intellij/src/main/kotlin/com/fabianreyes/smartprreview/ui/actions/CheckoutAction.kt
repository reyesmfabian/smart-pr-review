package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.ui.runWithRetry
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager

/** Checks out the open pull request's branch. Registered in plugin.xml, so the project comes from the event. */
class CheckoutAction : DumbAwareAction(message("action.checkout"), null, AllIcons.Vcs.Branch) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val current = e.project?.let { ReviewSession.getInstance(it).current }
        e.presentation.isEnabled = current != null && !current.checkedOut
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runWithRetry(project, message("checkout.progress"), {
            ReviewSession.getInstance(project).checkout { branch ->
                var ok = false
                ApplicationManager.getApplication().invokeAndWait {
                    ok = Messages.showOkCancelDialog(
                        message("checkout.resetQuestion", branch), message("checkout.resetTitle"), message("checkout.resetTitle"),
                        Messages.getCancelButton(), Messages.getWarningIcon(),
                    ) == Messages.OK
                }
                ok
            }
        }, done = { VirtualFileManager.getInstance().asyncRefresh(null) })
    }
}
