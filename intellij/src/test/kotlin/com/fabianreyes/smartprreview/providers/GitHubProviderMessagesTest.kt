package com.fabianreyes.smartprreview.providers

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GitHubProviderMessagesTest : BasePlatformTestCase() {
    private fun requiresSummary(body: String, vote: Vote) {
        val t = FakeTransport { emptyMap<String, Any>() }
        val p = GitHubProvider("octo", "app", { "tok" }, t)
        val e = org.junit.Assert.assertThrows(Exception::class.java) { p.publishReview(pr, body, listOf(draft("d1", 4, 4)), vote) }
        assertEquals("Write a review summary for this vote.", e.message)
        assertEquals(0, t.calls.size)
    }

    fun `test requires a summary to request changes`() = requiresSummary("  ", Vote.REQUEST_CHANGES)

    fun `test requires a summary for a Comment review even with drafts`() = requiresSummary("", Vote.COMMENT)

    fun `test rejects a vote GitHub does not support`() {
        val p = GitHubProvider("octo", "app", { "tok" }, FakeTransport { null })
        val e = org.junit.Assert.assertThrows(Exception::class.java) { p.publishReview(pr, "x", emptyList(), Vote.WAIT_FOR_AUTHOR) }
        assertEquals("GitHub does not support the vote \"waitForAuthor\".", e.message)
    }
}
