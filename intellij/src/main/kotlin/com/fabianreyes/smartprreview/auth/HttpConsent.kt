package com.fabianreyes.smartprreview.auth

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import java.util.concurrent.CompletableFuture

/**
 * Asks before a personal access token goes to an Azure DevOps Server over plain HTTP, where anyone on the
 * network can read it. A yes is remembered per server; after a no, the next request asks again.
 * Invoked as AzureDevOpsAuth's `beforePat`: it throws to keep the token.
 */
class HttpConsent(
    private val origin: String,
    private val remembered: () -> Boolean,
    private val remember: () -> Unit,
    private val ask: (origin: String) -> Boolean,
) : () -> Unit {
    private var pending: CompletableFuture<Boolean>? = null

    override fun invoke() {
        if (remembered()) return
        // Requests made at the same time (the views load in parallel) share one question.
        val question = synchronized(this) {
            pending ?: CompletableFuture.supplyAsync { ask(origin) }.also { future ->
                pending = future
                future.whenComplete { _, _ -> synchronized(this) { pending = null } }
            }
        }
        if (!question.join()) throw IllegalStateException(message("auth.http.refused", origin))
        remember()
    }

    companion object {
        /** The consent for `origin`, remembered in the IDE's application-level properties. */
        fun forOrigin(origin: String): HttpConsent {
            val key = "smartPrReview.httpAllowed:$origin"
            val properties = PropertiesComponent.getInstance()
            return HttpConsent(origin, { properties.getBoolean(key) }, { properties.setValue(key, true) }, ::askHttpDialog)
        }
    }
}

private fun askHttpDialog(origin: String): Boolean {
    var send = false
    ApplicationManager.getApplication().invokeAndWait {
        send = Messages.showOkCancelDialog(
            message("auth.http.question", origin),
            message("auth.pat.title"),
            message("auth.http.send"),
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.OK
    }
    return send
}
