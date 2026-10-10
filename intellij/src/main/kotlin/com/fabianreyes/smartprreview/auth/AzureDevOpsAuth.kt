package com.fabianreyes.smartprreview.auth

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.http.HttpError
import com.intellij.openapi.ui.Messages
import java.util.Base64

/**
 * A personal access token stored per host. Azure DevOps Services and Server alike: the IDE has no Microsoft
 * sign-in, so the token is the only credential (the VS Code version also accepts a Microsoft session).
 */
class AzureDevOpsAuth(
    private val host: String,
    private val secrets: Secrets = PasswordSafeSecrets,
    /** Runs before the personal access token is sent, and throws to keep it (see HttpConsent). */
    private val beforePat: (() -> Unit)? = null,
    /** Asks the user for a token; null when cancelled. */
    private val prompt: (host: String) -> String? = ::promptPatDialog,
) : Auth {
    private val key = "smartPrReview.azureDevOps.pat:$host"

    private fun pat(): String? = secrets.get(key)

    override fun isSignedIn(): Boolean = pat() != null

    override fun signIn(force: Boolean) = promptPat()

    override fun authorization(): String {
        val pat = pat() ?: throw HttpError(401, "Not signed in to Azure DevOps")
        beforePat?.invoke()
        return "Basic " + Base64.getEncoder().encodeToString(":$pat".toByteArray())
    }

    /** Asks for a personal access token. An empty answer removes the stored one; cancel keeps it. */
    fun promptPat() {
        val value = prompt(host) ?: return
        secrets.set(key, value.trim().ifEmpty { null })
    }
}

private fun promptPatDialog(host: String): String? =
    Messages.showPasswordDialog(message("auth.pat.prompt", host), message("auth.pat.title"))
