package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent

class ExitReviewAction() : ReviewAction(message("action.exitReview"), AllIcons.Actions.Exit) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = session(e) ?: return
        val prId = session.current?.pr?.id ?: return
        val git = session.git
        session.close()
        manageWorktrees(project, git, prId)
    }
}
