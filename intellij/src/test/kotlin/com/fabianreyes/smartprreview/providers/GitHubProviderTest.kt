package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.http.HttpError
import com.fabianreyes.smartprreview.http.obj
import com.fabianreyes.smartprreview.http.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubProviderTest {
    private fun provider(t: FakeTransport) = GitHubProvider("octo", "app", { "tok" }, t)

    @Test
    fun `lists pull requests waiting for my review`() {
        val t = FakeTransport {
            mapOf(
                "items" to listOf(
                    mapOf("number" to 7, "title" to "Fix", "user" to mapOf("login" to "ana"), "updated_at" to UPDATED, "html_url" to PR_URL),
                    mapOf("number" to 8, "title" to "Bot", "user" to null, "updated_at" to UPDATED, "html_url" to PR_URL),
                ),
            )
        }
        val prs = provider(t).listPullRequests(PullRequestFilter.ASSIGNED)
        assertEquals(listOf(PullRequestSummary(7, "Fix", "ana", UPDATED, PR_URL), PullRequestSummary(8, "Bot", "ghost", UPDATED, PR_URL)), prs)
        assertEquals("https://api.github.com/search/issues?q=repo%3Aocto%2Fapp%20is%3Apr%20is%3Aopen%20review-requested%3A%40me&sort=updated&order=desc&per_page=100", t.calls[0].url)
        assertEquals("Bearer tok", t.calls[0].headers["Authorization"])
    }

    @Test
    fun `maps a pull request and the refs to fetch, its branch is checked out under its own name`() {
        val t = FakeTransport { ghPull("octo/app") }
        val result = provider(t).getPullRequest(7)
        assertEquals("https://api.github.com/repos/octo/app/pulls/7", t.calls[0].url)
        assertEquals(
            pr.copy(fetchRefspecs = listOf("+refs/heads/main:refs/smart-pr-review/7/base", "+refs/pull/7/head:refs/smart-pr-review/7/head")),
            result,
        )
        assertEquals("fix", result.checkoutBranch)
        assertEquals("fix", result.headBranch)
    }

    @Test
    fun `a fork's pull request (or one whose fork is gone) is checked out as pr id, tracking nothing`() {
        for (headRepo in listOf("ana/app", null)) {
            val result = provider(FakeTransport { ghPull(headRepo) }).getPullRequest(7)
            assertEquals("pr/7", result.checkoutBranch)
            assertNull(result.headBranch)
        }
    }

    @Test
    fun `reads every page of review threads`() {
        var page = 0
        val t = FakeTransport {
            val reviewThreads = if (page++ == 0) {
                mapOf("pageInfo" to mapOf("hasNextPage" to true, "endCursor" to "c1"), "nodes" to listOf(thread("T1")))
            } else {
                mapOf(
                    "pageInfo" to mapOf("hasNextPage" to false, "endCursor" to null),
                    "nodes" to listOf(
                        thread("T2", "line" to null, "isOutdated" to true, "originalLine" to 7, "originalStartLine" to 6),
                        thread("T3", "line" to 2, "diffSide" to "LEFT", "isResolved" to true),
                    ),
                )
            }
            mapOf("data" to mapOf("repository" to mapOf("pullRequest" to mapOf("reviewThreads" to reviewThreads))))
        }
        val threads = provider(t).getThreads(pr)
        assertEquals(
            listOf(
                listOf("T1", Side.HEAD, 5, false, false),
                listOf("T2", Side.HEAD, null, true, false),
                listOf("T3", Side.BASE, 2, false, true),
            ),
            threads.map { listOf(it.id, it.side, it.line, it.isOutdated, it.isResolved) },
        )
        assertEquals(listOf(ThreadComment("T1-c", "ana", "Why?", UPDATED)), threads[0].comments)
        assertEquals(listOf(null, Thread.Original("d".repeat(40), 7, 6), null), threads.map { it.original })
        assertEquals("c1", t.body(1).obj("variables").str("after"))
    }

    @Test
    fun `replies, resolves and reopens threads`() {
        val t = FakeTransport { mapOf("data" to emptyMap<String, Any>()) }
        val p = provider(t)
        p.reply(pr, "T1", "Done")
        p.setThreadResolved(pr, "T1", true)
        p.setThreadResolved(pr, "T1", false)
        assertTrue(t.body(0).str("query")!!.contains("addPullRequestReviewThreadReply"))
        assertEquals("""{"id":"T1","body":"Done"}""", t.body(0).obj("variables").toString())
        assertTrue(Regex("""\bresolveReviewThread""").containsMatchIn(t.body(1).str("query")!!))
        assertTrue(t.body(2).str("query")!!.contains("unresolveReviewThread"))
        assertEquals("https://api.github.com/graphql", t.calls[0].url)
        assertEquals("POST", t.calls[0].method)
    }

    @Test
    fun `surfaces GraphQL errors`() {
        val t = FakeTransport { mapOf("errors" to listOf(mapOf("message" to "Resource not accessible"))) }
        val e = assertThrows(IllegalStateException::class.java) { provider(t).reply(pr, "T1", "x") }
        assertEquals("Resource not accessible", e.message)
    }

    @Test
    fun `reads and updates viewed files`() {
        val t = FakeTransport {
            mapOf(
                "data" to mapOf(
                    "repository" to mapOf(
                        "pullRequest" to mapOf(
                            "files" to mapOf(
                                "pageInfo" to mapOf("hasNextPage" to false, "endCursor" to null),
                                "nodes" to listOf(
                                    mapOf("path" to "a.ts", "viewerViewedState" to "VIEWED"),
                                    mapOf("path" to "b.ts", "viewerViewedState" to "DISMISSED"),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        val p = provider(t)
        assertEquals(setOf("a.ts"), p.getViewedFiles(pr))
        p.setFileViewed(pr, "b.ts", true)
        assertTrue(t.body(1).str("query")!!.contains("markFileAsViewed"))
        assertEquals("""{"id":"PR_kw7","path":"b.ts"}""", t.body(1).obj("variables").toString())
    }

    @Test
    fun `publishes a review with single and multi-line comments`() {
        val t = FakeTransport { mapOf("id" to 1) }
        val result = provider(t).publishReview(pr, "LGTM", listOf(draft("d1", 4, 4), draft("d2", 7, 9)), Vote.APPROVE)
        assertEquals("https://api.github.com/repos/octo/app/pulls/7/reviews", t.calls[0].url)
        assertEquals(
            """{"commit_id":"$HEAD_SHA","event":"APPROVE","body":"LGTM","comments":[""" +
                """{"path":"src/app.ts","body":"note d1","side":"RIGHT","line":4},""" +
                """{"path":"src/app.ts","body":"note d2","side":"RIGHT","line":9,"start_line":7,"start_side":"RIGHT"}]}""",
            t.calls[0].body,
        )
        assertEquals(PublishResult(listOf("d1", "d2"), emptyList()), result)
    }

    @Test
    fun `propagates a rejected review`() {
        val t = FakeTransport { HttpError(422, "Can not approve your own pull request") }
        val e = assertThrows(HttpError::class.java) { provider(t).publishReview(pr, "", listOf(draft("d1", 4, 4)), Vote.APPROVE) }
        assertEquals(422, e.status)
        assertTrue(e.message!!.contains("Can not approve your own"))
        // An empty summary is omitted from the body for a vote that does not require it.
        assertTrue(!t.calls[0].body!!.contains("\"body\":\"\""))
    }
}
