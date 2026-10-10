package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.http.HttpError
import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AzureDevOpsPublishTest : BasePlatformTestCase() {
    private fun provider(t: FakeTransport) = AzureDevOpsProvider(cloud, { "Bearer tok" }, MemoryViewedFiles(), t)

    private fun json(text: String) = JsonParser.parseString(text)

    private fun writes(t: FakeTransport) = t.calls.withIndex().filter { it.value.method == "POST" || it.value.method == "PUT" }

    fun `test publishes drafts anchored to the latest iteration, then the vote, then the summary`() {
        val t = FakeTransport {
            when {
                it.url.contains("/iterations/2/changes") -> mapOf("changeEntries" to listOf(mapOf("changeTrackingId" to 5, "item" to mapOf("path" to "/src/app.ts"))), "nextSkip" to 0)
                it.url.contains("/iterations?") -> mapOf("value" to listOf(mapOf("id" to 1), mapOf("id" to 2)))
                it.url.contains("/_apis/connectionData") -> mapOf("authenticatedUser" to mapOf("id" to "me-1"))
                else -> "{}"
            }
        }
        val result = provider(t).publishReview(adoPr, "LGTM", listOf(draft("d1", 4, 6).copy(startColumn = 3, endColumn = 8)), Vote.APPROVE)
        assertEquals(PublishResult(listOf("d1", "vote", "summary"), emptyList()), result)
        val w = writes(t)
        assertEquals(listOf("POST", "PUT", "POST"), w.map { it.value.method })
        assertEquals(
            listOf("$REPO_API/pullRequests/7/threads", "$REPO_API/pullRequests/7/reviewers/me-1", "$REPO_API/pullRequests/7/threads"),
            w.map { it.value.url.substringBefore("?") },
        )
        assertTrue(t.urls().any { it.contains("/iterations/2/changes?\$top=2000&\$skip=0&\$compareTo=0") })
        assertEquals(
            json(
                """{"comments":[{"parentCommentId":0,"content":"note d1","commentType":1}],"status":1,
                "threadContext":{"filePath":"/src/app.ts","rightFileStart":{"line":4,"offset":3},"rightFileEnd":{"line":6,"offset":8}},
                "pullRequestThreadContext":{"changeTrackingId":5,"iterationContext":{"firstComparingIteration":1,"secondComparingIteration":2}}}""",
            ),
            t.body(w[0].index),
        )
        assertEquals(json("""{"vote":10,"id":"me-1"}"""), t.body(w[1].index))
        assertEquals(json("""{"comments":[{"parentCommentId":0,"content":"LGTM","commentType":1}],"status":1}"""), t.body(w[2].index))
    }

    fun `test holds back the vote and summary when a draft fails, so the retry sends them together`() {
        var posts = 0
        val t = FakeTransport {
            when {
                it.url.contains("/iterations/1/changes") -> mapOf("changeEntries" to emptyList<Any>())
                it.url.contains("/iterations?") -> mapOf("value" to listOf(mapOf("id" to 1)))
                else -> if (++posts == 2) HttpError(500, "boom") else "{}"
            }
        }
        val result = provider(t).publishReview(adoPr, "LGTM", listOf(draft("d1", 4, 4), draft("d2", 8, 8)), Vote.APPROVE)
        assertEquals(listOf("d1"), result.published)
        assertEquals(listOf("d2"), result.failed.map { it.id })
        assertTrue(result.failed[0].error, result.failed[0].error.contains("boom"))
        assertEquals(0, t.calls.count { it.method == "PUT" })
        assertEquals(2, posts)
    }

    fun `test stops at the first failed draft, with 3 of 5 failing 2 are published and 3 stay as drafts`() {
        var posts = 0
        val t = FakeTransport {
            when {
                it.url.contains("/iterations/1/changes") -> mapOf("changeEntries" to emptyList<Any>())
                it.url.contains("/iterations?") -> mapOf("value" to listOf(mapOf("id" to 1)))
                else -> if (++posts == 3) HttpError(500, "boom") else "{}"
            }
        }
        val result = provider(t).publishReview(adoPr, "", (1..5).map { draft("d$it", it, it) }, Vote.COMMENT)
        assertEquals(listOf("d1", "d2"), result.published)
        assertEquals(listOf(PublishResult.Failure("d3", "src/app.ts:3 — Request failed (500): boom")), result.failed)
        assertEquals(3, posts)
    }

    fun `test reports a failed vote without sending the summary`() {
        val t = FakeTransport {
            when {
                it.url.contains("/_apis/connectionData") -> mapOf("authenticatedUser" to mapOf("id" to "me-1"))
                it.method == "PUT" -> HttpError(403, "not a reviewer")
                else -> "{}"
            }
        }
        val result = provider(t).publishReview(adoPr, "LGTM", emptyList(), Vote.REJECT)
        assertEquals(listOf("vote"), result.failed.map { it.id })
        assertTrue(result.failed[0].error, result.failed[0].error.startsWith("Vote — Permission denied: not a reviewer"))
        assertEquals(0, t.calls.count { it.method == "POST" })
    }

    fun `test sends no vote for a plain comment, and refuses an empty review`() {
        val t = FakeTransport { "{}" }
        assertEquals(PublishResult(listOf("summary"), emptyList()), provider(t).publishReview(adoPr, "Thanks", emptyList(), Vote.COMMENT))
        val e = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { provider(t).publishReview(adoPr, " ", emptyList(), Vote.COMMENT) }
        assertEquals("Nothing to publish: add a comment, a summary or a vote.", e.message)
        assertEquals(1, t.calls.size)
    }
}
