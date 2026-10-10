package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.SmartPrReviewRepository
import com.fabianreyes.smartprreview.http.HttpError
import com.fabianreyes.smartprreview.http.describeError
import com.fabianreyes.smartprreview.review.ReviewSession
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Runs the blocking [work] in a background task and hands its result to [done] on the EDT; [onFailure] runs first, on the EDT, whenever it throws. A failure is shown
 * with Sign In when the server answered 401 and with Retry otherwise.
 */
fun <T> runWithRetry(project: Project, title: String, work: () -> T, onFailure: () -> Unit = {}, done: (T) -> Unit = {}) {
    object : Task.Backgroundable(project, title, false) {
        private var result: Any? = null

        override fun run(indicator: ProgressIndicator) {
            result = work()
        }

        @Suppress("UNCHECKED_CAST")
        override fun onSuccess() = done(result as T)

        // Runs on the EDT.
        override fun onThrowable(error: Throwable) {
            onFailure()
            val unauthorized = error is HttpError && error.status == 401
            val answer = Messages.showOkCancelDialog(
                project, describeError(error), title,
                message(if (unauthorized) "action.signIn" else "action.retry"), message("action.cancel"), Messages.getErrorIcon(),
            )
            if (answer != Messages.OK) return
            if (unauthorized) {
                val repository = SmartPrReviewRepository.getInstance(project)
                (repository.state as? RepositoryState.Ready)?.auth?.signIn(force = true)
                repository.refresh()
            } else {
                runWithRetry(project, title, work, onFailure, done)
            }
        }
    }.queue()
}

/** Opens the pull request for review (fetches it from the remote) in the background; [finished] runs on the EDT once it opened or failed. */
fun openPullRequest(project: Project, id: Int, finished: () -> Unit = {}) =
    runWithRetry(project, message("pr.opening", id.toString()), { ReviewSession.getInstance(project).open(id) }, onFailure = finished, done = { finished() })
