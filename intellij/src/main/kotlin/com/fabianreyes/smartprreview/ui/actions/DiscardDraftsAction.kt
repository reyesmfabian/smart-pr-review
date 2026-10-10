package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.ui.runWithRetry
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class DiscardDraftsAction() : ReviewAction(message("action.discardDrafts"), AllIcons.Actions.GC) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = session(e)?.current?.drafts?.all()?.isNotEmpty() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = session(e) ?: return
        val ids = session.current?.drafts?.all()?.map { it.id }?.takeIf { it.isNotEmpty() } ?: return
        val answer = Messages.showYesNoDialog(
            project, message("draft.discardQuestion", ids.size), message("action.discardDrafts"),
            message("draft.discard"), message("action.cancel"), Messages.getWarningIcon(),
        )
        if (answer == Messages.YES) runWithRetry(project, message("action.discardDrafts"), { session.removeDrafts(ids) })
    }
}
