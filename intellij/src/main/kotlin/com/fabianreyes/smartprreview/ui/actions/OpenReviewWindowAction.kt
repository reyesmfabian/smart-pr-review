package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.ui.ReviewWindow
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent

class OpenReviewWindowAction : ReviewAction(message("action.openReviewWindow"), AllIcons.Actions.MoveToWindow) {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { ReviewWindow.getInstance(it).show() }
    }
}
