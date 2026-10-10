package com.fabianreyes.smartprreview.auth

import com.fabianreyes.smartprreview.http.HttpError
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GitHubAuthTest : BasePlatformTestCase() {
    fun testPluginAccountWinsAndThePatIsTheFallback() {
        val secrets = MemorySecrets()
        var pluginToken: String? = null
        val source = object : GitHubTokenSource {
            override fun token(project: Project) = pluginToken
            override fun signIn(project: Project): Boolean? = true
        }
        val auth = GitHubAuth(project, secrets, sources = { listOf(source) }, prompt = { "pat-1" })
        assertFalse(auth.isSignedIn())
        assertEquals(401, org.junit.Assert.assertThrows(HttpError::class.java) { auth.authorization() }.status)
        auth.promptPat()
        assertEquals("pat-1", secrets.map["smartPrReview.github.pat"])
        assertEquals("Bearer pat-1", auth.authorization())
        pluginToken = "gh-1"
        assertEquals("Bearer gh-1", auth.authorization())
    }

    fun testSignInUsesThePluginWhenPresentAndThePatPromptOtherwise() {
        var pluginSignIns = 0
        var prompts = 0
        val source = object : GitHubTokenSource {
            override fun token(project: Project): String? = null
            override fun signIn(project: Project): Boolean? { pluginSignIns++; return false }
        }
        GitHubAuth(project, MemorySecrets(), sources = { listOf(source) }, prompt = { prompts++; "x" }).signIn(force = false)
        assertEquals(1, pluginSignIns)
        assertEquals(0, prompts)

        val unsupported = object : GitHubTokenSource {
            override fun token(project: Project): String? = null
            override fun signIn(project: Project): Boolean? = null
        }
        GitHubAuth(project, MemorySecrets(), sources = { listOf(unsupported) }, prompt = { prompts++; "y" }).signIn(force = false)
        assertEquals(1, prompts)
        prompts = 0

        val secrets = MemorySecrets()
        GitHubAuth(project, secrets, sources = { emptyList() }, prompt = { prompts++; "  tok " }).signIn(force = true)
        assertEquals(1, prompts)
        assertEquals("tok", secrets.map["smartPrReview.github.pat"])
    }

    fun testThePluginTokenSourceIsRegisteredWithTheBundledGitHubPlugin() {
        // The test IDE loads the bundled GitHub plugin, so the optional descriptor is active and no account exists.
        val sources = GitHubTokenSource.EP_NAME.extensionList
        assertEquals(1, sources.size)
        assertNull(sources.single().token(project))
    }
}
