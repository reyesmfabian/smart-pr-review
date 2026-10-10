package com.fabianreyes.smartprreview.auth.github

import com.fabianreyes.smartprreview.auth.GitHubTokenSource
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.AuthorizationType
import org.jetbrains.plugins.github.authentication.GHAccountsUtil
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager

private val LOG = logger<GitHubPluginTokenSource>()

/** Loaded only with org.jetbrains.plugins.github (see META-INF/smartPrReview-github.xml). */
class GitHubPluginTokenSource : GitHubTokenSource {
    override fun token(project: Project): String? {
        val github = GHAccountsUtil.accounts.filter { it.server.isGithubDotCom }
        val account = GHAccountsUtil.getDefaultAccount(project)?.takeIf { it in github } ?: github.singleOrNull() ?: github.firstOrNull() ?: return null
        // findCredentials is suspend; this runs on a pooled thread (see Auth.isSignedIn).
        return runBlocking { service<GHAccountManager>().findCredentials(account) }?.ifEmpty { null }
    }

    /**
     * Signs in through the browser (OAuth), so organization SSO applies; the one-argument overload opens the token
     * dialog instead. All five arguments are passed: that overload has the same signature in 251 and 262, while the
     * Kotlin `$default` bridge a shorter call compiles to does not (262 added a GHLoginSource parameter).
     */
    override fun signIn(project: Project): Boolean? = try {
        GHAccountsUtil.requestNewAccount(GithubServerPath.DEFAULT_SERVER, null, project, null, AuthorizationType.OAUTH) != null
    } catch (e: LinkageError) {
        LOG.info("GitHub plugin sign-in unavailable in this IDE; using the token prompt", e)
        null
    }
}
