package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.http.HttpError
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AzureDevOpsProviderMessagesTest : BasePlatformTestCase() {
    private fun provider(t: FakeTransport) = AzureDevOpsProvider(cloud, { "Bearer tok" }, MemoryViewedFiles(), t)

    fun `test adds the personal access token scope to Azure DevOps permission errors`() {
        val t = FakeTransport { HttpError(403, "TF401027: You need the Git GenericRead permission", mapOf("x-a" to "b")) }
        val e = org.junit.Assert.assertThrows(HttpError::class.java) { provider(t).listPullRequests(PullRequestFilter.ALL) }
        assertEquals(403, e.status)
        assertEquals("b", e.header("x-a"))
        assertTrue(e.message!!, Regex("GenericRead.*Code \\(Read & Write\\)").containsMatchIn(e.message!!))
    }

    fun `test asks to retry while Azure DevOps has not computed the pull request commits`() {
        val t = FakeTransport { adoPullRequest("lastMergeSourceCommit" to null) }
        val e = org.junit.Assert.assertThrows(Exception::class.java) { provider(t).getPullRequest(7) }
        assertEquals("Azure DevOps is still preparing this pull request. Try again in a moment.", e.message)
    }
}
