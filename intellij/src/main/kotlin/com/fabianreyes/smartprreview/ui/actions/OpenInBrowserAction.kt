package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.ui.browserUrl
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnActionEvent

class OpenInBrowserAction() : ReviewAction(message("action.openInBrowser"), AllIcons.Nodes.PpWeb) {
    override fun actionPerformed(e: AnActionEvent) {
        session(e)?.current?.pr?.url?.let(::browserUrl)?.let(BrowserUtil::browse)
    }
}
