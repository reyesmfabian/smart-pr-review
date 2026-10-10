package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.review.ReviewSession
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.ui.JBUI
import javax.swing.Action
import javax.swing.JComponent

/** Reuses the sidebar and native diff viewer in one non-modal, resizable review window. EDT only. */
@Service(Service.Level.PROJECT)
class ReviewWindow(private val project: Project) : Disposable {
    private var dialog: ReviewDialog? = null
    val isOpen: Boolean get() = dialog?.isVisible == true

    fun show() {
        ReviewSession.getInstance(project).require()
        dialog?.takeIf { it.isVisible }?.let { it.toFront(); return }
        ReviewDialog().also { dialog = it; it.show() }
    }

    fun showDiff(request: SimpleDiffRequest, ticket: Int): Boolean {
        val d = dialog?.takeIf { it.isVisible } ?: return false
        if (ticket >= d.shownTicket) {
            d.shownTicket = ticket
            d.diff.setRequest(request)
        }
        return true
    }

    override fun dispose() { dialog?.close(DialogWrapper.CANCEL_EXIT_CODE) }

    private inner class ReviewDialog : DialogWrapper(project, false) {
        val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        var shownTicket = 0
        private val session = ReviewSession.getInstance(project)
        private val panel = CurrentPullRequestPanel(project, session)
        private var revision = ""

        init {
            isModal = false
            title = message("action.openReviewWindow")
            setCancelButtonText(message("reviewWindow.close"))
            Disposer.register(disposable, panel)
            session.onChange(disposable) { update() }
            init()
            update()
        }

        private fun update() {
            val c = session.current ?: return close(CANCEL_EXIT_CODE)
            title = "#${c.pr.id} ${c.pr.title} — Smart PR Review"
            val next = "${c.pr.id}:${c.mergeBase}:${c.pr.headSha}"
            if (revision != next) diff.setRequest(null)
            revision = next
        }

        override fun createCenterPanel(): JComponent = OnePixelSplitter(false, 0.3f).apply {
            firstComponent = panel
            secondComponent = diff.component
            preferredSize = JBUI.size(1200, 750)
            DataManager.registerDataProvider(this) { id -> if (CommonDataKeys.PROJECT.`is`(id)) project else null }
        }

        override fun createActions(): Array<Action> = arrayOf(cancelAction)
        override fun getDimensionServiceKey() = "SmartPrReview.ReviewWindow"
    }

    companion object {
        fun getInstance(project: Project): ReviewWindow = project.service()
    }
}
