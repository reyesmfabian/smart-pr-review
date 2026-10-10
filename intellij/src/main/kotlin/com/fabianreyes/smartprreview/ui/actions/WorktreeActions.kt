package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.SmartPrReviewRepository
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.review.ReviewWorktree
import com.fabianreyes.smartprreview.review.checkReviewWorktree
import com.fabianreyes.smartprreview.review.createReviewWorktree
import com.fabianreyes.smartprreview.review.removeReviewWorktree
import com.fabianreyes.smartprreview.review.reviewWorktrees
import com.fabianreyes.smartprreview.ui.runWithRetry
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.nio.file.Path

class CreateWorktreeAction : ReviewAction(message("worktree.create"), null) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = ReviewSession.getInstance(project)
        val pr = session.require().pr
        val git = session.git
        runWithRetry(project, message("worktree.create"), { createReviewWorktree(git, pr.id, pr.headSha) }, done = { worktree ->
            if (Messages.showOkCancelDialog(project, message("worktree.created", worktree.path), message("worktree.create"),
                    message("worktree.open"), message("action.cancel"), Messages.getInformationIcon()) == Messages.OK) openWorktree(project, git, worktree)
        })
    }
}

class ManageWorktreesAction : DumbAwareAction(message("worktree.manage")) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { SmartPrReviewRepository.getInstance(it).state is RepositoryState.Ready } == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ready = SmartPrReviewRepository.getInstance(project).state as? RepositoryState.Ready ?: return
        manageWorktrees(project, ready.git)
    }
}

/** Also called on Exit Review; cancelling keeps the worktree available through the menu. */
fun manageWorktrees(project: Project, git: Git, prId: Int? = null) {
    runWithRetry(project, message("worktree.manage"), { reviewWorktrees(git).filter { prId == null || it.prId == prId } }, done = { worktrees ->
        if (worktrees.isEmpty()) {
            if (prId == null) Messages.showInfoMessage(project, message("worktree.none"), message("worktree.manage"))
        } else {
            JBPopupFactory.getInstance().createPopupChooserBuilder(worktrees).setTitle(message("worktree.manage"))
                .setItemChosenCallback { w ->
                    when (Messages.showDialog(project, w.path.toString(), message("worktree.manage"),
                        arrayOf(message("worktree.open"), message("worktree.remove"), message("action.cancel")), 0, Messages.getQuestionIcon())) {
                        0 -> openWorktree(project, git, w)
                        1 -> if (Messages.showYesNoDialog(project, message("worktree.confirmRemove", w.path), message("worktree.remove"), Messages.getWarningIcon()) == Messages.YES) {
                            // An open project can have unsaved buffers invisible to git. Close it before removing its files.
                            val openPaths = ProjectManager.getInstance().openProjects.mapNotNull { it.basePath }.map { Path.of(it) }
                            runWithRetry(project, message("worktree.remove"), {
                                check(openPaths.none { it.toRealPath().startsWith(w.path.toRealPath()) }) { message("worktree.closeFirst") }
                                removeReviewWorktree(git, w)
                            })
                        }
                    }
                }.createPopup().showCenteredInCurrentWindow(project)
        }
    })
}

private fun openWorktree(project: Project, git: Git, worktree: ReviewWorktree) {
    runWithRetry(project, message("worktree.open"), { checkReviewWorktree(git, worktree) }, done = {
        ProjectUtil.openOrImport(worktree.path.toString(), null, true)
    })
}
