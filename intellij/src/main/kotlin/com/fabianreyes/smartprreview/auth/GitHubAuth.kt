package com.fabianreyes.smartprreview.auth

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.http.HttpError
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/** A GitHub token from the JetBrains GitHub plugin's accounts; registered only when that plugin is installed. */
interface GitHubTokenSource {
    /** Token of the github.com account the user picked (or the only one); null when there is none. Blocking. */
    fun token(project: Project): String?

    /** Opens the plugin's login dialog (EDT); true when an account was added, null when this IDE's plugin can't be driven (use the PAT prompt). */
    fun signIn(project: Project): Boolean?

    companion object {
        val EP_NAME: ExtensionPointName<GitHubTokenSource> = ExtensionPointName.create("com.fabianreyes.smartprreview.githubTokenSource")
    }
}

/**
 * GitHub sign-in: the JetBrains GitHub plugin's account when that plugin is installed and has one, otherwise a
 * personal access token (repo scope) stored in Secrets.
 */
class GitHubAuth(
    private val project: Project,
    private val secrets: Secrets = PasswordSafeSecrets,
    private val sources: () -> List<GitHubTokenSource> = { GitHubTokenSource.EP_NAME.extensionList },
    /** Asks the user for a token; null when cancelled. */
    private val prompt: () -> String? = ::promptGitHubPatDialog,
) : Auth {
    private val key = "smartPrReview.github.pat"

    private fun source(): GitHubTokenSource? = sources().firstOrNull()

    private fun token(): String? = source()?.token(project) ?: secrets.get(key)

    override fun isSignedIn(): Boolean = token() != null

    override fun signIn(force: Boolean) {
        // ponytail: `force` runs the same flow; the plugin's dialog and the token prompt both replace credentials.
        val source = source()
        if (source?.signIn(project) == null) promptPat()
    }

    override fun authorization(): String = "Bearer " + (token() ?: throw HttpError(401, "Not signed in to GitHub"))

    /** Asks for a personal access token. An empty answer removes the stored one; cancel keeps it. */
    fun promptPat() {
        val value = prompt() ?: return
        secrets.set(key, value.trim().ifEmpty { null })
    }
}

private fun promptGitHubPatDialog(): String? =
    Messages.showPasswordDialog(message("auth.github.pat.prompt"), message("auth.github.pat.title"))
