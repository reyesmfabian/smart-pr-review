package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.review.ReviewSession
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.Icon

/** Base of the actions that work on the open pull request: only enabled while there is one. */
abstract class ReviewAction(text: String, icon: Icon?) : DumbAwareAction(text, null, icon) {
    protected fun session(e: AnActionEvent): ReviewSession? = e.project?.let(ReviewSession::getInstance)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = session(e)?.current != null
    }
}
