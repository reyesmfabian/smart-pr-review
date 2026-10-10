package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.auth.Auth
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.fabianreyes.smartprreview.remote.RemoteInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    private fun auth(header: String) = object : Auth {
        override fun isSignedIn() = true
        override fun signIn(force: Boolean) {}
        override fun authorization() = header
    }

    private val github = RemoteInfo(ProviderKind.GITHUB, "github.com", "https://github.com", "octo", repo = "repo")

    @Test
    fun `providerFor picks the provider of the remote kind`() {
        val gh = providerFor(github, auth("Bearer t"), MemoryViewedFiles())
        assertTrue(gh is GitHubProvider)
        assertEquals(ProviderKind.GITHUB, gh.kind)
        val ado = providerFor(cloud, auth("Basic x"), MemoryViewedFiles())
        assertTrue(ado is AzureDevOpsProvider)
        assertEquals(ProviderKind.AZURE_DEVOPS, ado.kind)
    }

    @Test
    fun `githubToken strips the Bearer prefix`() {
        assertEquals("abc", githubToken(auth("Bearer abc")))
    }
}
