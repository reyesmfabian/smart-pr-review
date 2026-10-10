package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.http.HttpCall
import com.fabianreyes.smartprreview.http.HttpError
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.fabianreyes.smartprreview.remote.RemoteInfo
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MemoryViewedFiles : ViewedFilesStore {
    private val data = mutableMapOf<String, Set<String>>()
    override fun get(key: String): Set<String> = data[key].orEmpty()
    override fun set(key: String, files: Set<String>) {
        data[key] = files
    }
}

internal val cloud = RemoteInfo(ProviderKind.AZURE_DEVOPS, "dev.azure.com", "https://dev.azure.com", "contoso", "My Project", "web")
internal const val REPO_API = "https://dev.azure.com/contoso/My%20Project/_apis/git/repositories/web"
private val ADO_BASE = "b".repeat(40)
private val ADO_HEAD = "c".repeat(40)
private const val ADO_URL = "https://dev.azure.com/contoso/My%20Project/_git/web/pullrequest/7"

internal val adoPr = PullRequest(
    summary = PullRequestSummary(7, "Fix", "Ana", UPDATED, ADO_URL),
    body = "", isDraft = false, baseSha = ADO_BASE, headSha = ADO_HEAD, fetchRefspecs = emptyList(),
    checkoutBranch = "feature/fix", headBranch = "feature/fix", nodeId = "7",
)

internal fun adoPullRequest(vararg overrides: Pair<String, Any?>): Map<String, Any?> = mapOf(
    "pullRequestId" to 7, "title" to "Fix", "description" to "Details", "createdBy" to mapOf("displayName" to "Ana"),
    "creationDate" to UPDATED, "sourceRefName" to "refs/heads/feature/fix", "targetRefName" to "refs/heads/main",
    "lastMergeSourceCommit" to mapOf("commitId" to ADO_HEAD), "lastMergeTargetCommit" to mapOf("commitId" to ADO_BASE),
) + overrides

class AzureDevOpsProviderTest {
    private fun provider(t: FakeTransport, remote: RemoteInfo = cloud, auth: () -> String = { "Bearer tok" }) =
        AzureDevOpsProvider(remote, auth, MemoryViewedFiles(), t)

    private fun param(url: String, name: String) =
        url.substringAfter('?').split('&').map { it.split('=', limit = 2) }.firstOrNull { it[0] == name }?.getOrNull(1)

    private val me1 = mapOf("authenticatedUser" to mapOf("id" to "me-1"))

    @Test
    fun `lists active pull requests waiting for my review`() {
        val t = FakeTransport { if (it.url.contains("/_apis/connectionData")) me1 else if (it.url.contains("/teams?")) mapOf("value" to emptyList<Any>()) else mapOf("value" to listOf(adoPullRequest())) }
        assertEquals(listOf(PullRequestSummary(7, "Fix", "Ana", UPDATED, ADO_URL)), provider(t).listPullRequests(PullRequestFilter.ASSIGNED))
        assertEquals("https://dev.azure.com/contoso/_apis/connectionData", t.calls[0].url)
        val list = t.calls.single { it.url.contains("/pullrequests?") }
        assertTrue(list.url.startsWith("$REPO_API/pullrequests?"))
        assertEquals("active", param(list.url, "searchCriteria.status"))
        assertEquals("me-1", param(list.url, "searchCriteria.reviewerId"))
        assertEquals("100", param(list.url, "\$top"))
        assertEquals("6.0", param(list.url, "api-version"))
        assertEquals("Bearer tok", list.headers["Authorization"])
    }

    @Test
    fun `Assigned to me includes pull requests assigned to my teams, newest first, without duplicates`() {
        fun pr(id: Int, date: String) = adoPullRequest("pullRequestId" to id, "creationDate" to date)
        val byReviewer = mapOf(
            "me-1" to listOf(pr(1, "2026-09-01T00:00:00Z"), pr(2, "2026-09-03T00:00:00Z")),
            "team-1" to listOf(pr(2, "2026-09-03T00:00:00Z"), pr(3, "2026-09-02T00:00:00Z")),
        )
        val t = FakeTransport {
            when {
                it.url.contains("/_apis/connectionData") -> me1
                it.url.contains("/teams?") -> mapOf("value" to listOf(mapOf("id" to "team-1", "name" to "Web")))
                else -> mapOf("value" to byReviewer[param(it.url, "searchCriteria.reviewerId")].orEmpty())
            }
        }
        assertEquals(listOf(2, 3, 1), provider(t).listPullRequests(PullRequestFilter.ASSIGNED).map { it.id })
        assertTrue(t.urls().contains("https://dev.azure.com/contoso/_apis/projects/My%20Project/teams?\$mine=true&api-version=6.0"))
        // The queries run one after another: me, then the team.
        val reviewers = t.urls().filter { it.contains("/pullrequests?") }.map { param(it, "searchCriteria.reviewerId") }
        assertEquals(listOf("me-1", "team-1"), reviewers)
    }

    @Test
    fun `Assigned to me falls back to my own reviews when my teams cannot be read`() {
        val t = FakeTransport {
            when {
                it.url.contains("/_apis/connectionData") -> me1
                it.url.contains("/teams?") -> HttpError(403, "token lacks project scope")
                else -> mapOf("value" to listOf(adoPullRequest()))
            }
        }
        assertEquals(listOf(7), provider(t).listPullRequests(PullRequestFilter.ASSIGNED).map { it.id })
    }

    @Test
    fun `asks who the user is again when the credentials change`() {
        val t = FakeTransport {
            if (it.url.contains("/_apis/connectionData")) {
                mapOf("authenticatedUser" to mapOf("id" to if (it.headers["Authorization"] == "Basic A") "me-A" else "me-B"))
            } else {
                mapOf("value" to emptyList<Any>())
            }
        }
        var authorization = "Basic A"
        val ado = provider(t, auth = { authorization })
        ado.listPullRequests(PullRequestFilter.ASSIGNED)
        ado.listPullRequests(PullRequestFilter.CREATED)
        authorization = "Basic B"
        ado.listPullRequests(PullRequestFilter.ASSIGNED)
        val ids = t.urls().filter { it.contains("searchCriteria.status") }.map { param(it, "searchCriteria.reviewerId") ?: param(it, "searchCriteria.creatorId") }
        assertEquals(listOf("me-A", "me-A", "me-B"), ids)
        assertEquals(2, t.urls().count { it.contains("/_apis/connectionData") })
    }

    @Test
    fun `forgets the identity after a failed lookup`() {
        var fail = true
        val t = FakeTransport {
            if (it.url.contains("/_apis/connectionData")) (if (fail) HttpError(500, "boom") else me1) else mapOf("value" to emptyList<Any>())
        }
        val ado = provider(t)
        assertThrows(HttpError::class.java) { ado.listPullRequests(PullRequestFilter.CREATED) }
        fail = false
        ado.listPullRequests(PullRequestFilter.CREATED)
        assertEquals(2, t.urls().count { it.contains("/_apis/connectionData") })
    }

    @Test
    fun `builds Azure DevOps Server URLs from the remote origin and collection`() {
        val t = FakeTransport { mapOf("value" to emptyList<Any>()) }
        val server = RemoteInfo(ProviderKind.AZURE_DEVOPS, "tfs.contoso.com", "http://tfs.contoso.com:8080", "tfs/DefaultCollection", "My Project", "web")
        provider(t, server).listPullRequests(PullRequestFilter.ALL)
        assertTrue(t.calls[0].url.startsWith("http://tfs.contoso.com:8080/tfs/DefaultCollection/My%20Project/_apis/git/repositories/web/pullrequests?"))
    }

    @Test
    fun `maps a pull request and fetches its source branch`() {
        val result = provider(FakeTransport { adoPullRequest() }).getPullRequest(7)
        assertEquals(
            adoPr.copy(
                body = "Details",
                fetchRefspecs = listOf("+refs/heads/main:refs/smart-pr-review/7/base", "+refs/heads/feature/fix:refs/smart-pr-review/7/head"),
            ),
            result,
        )
    }

    @Test
    fun `fetches a fork pull request head from the fork repository and checks it out as pr slash id`() {
        val forkUrl = "https://dev.azure.com/ana/My%20Project/_git/web-fork"
        val forkSource = mapOf("name" to "refs/heads/fix", "repository" to mapOf("remoteUrl" to forkUrl))
        val result = provider(FakeTransport { adoPullRequest("forkSource" to forkSource) }).getPullRequest(7)
        assertEquals(listOf("+refs/heads/main:refs/smart-pr-review/7/base"), result.fetchRefspecs)
        assertEquals(PullRequest.HeadFetch(forkUrl, "+refs/heads/fix:refs/smart-pr-review/7/head"), result.headFetch)
        assertEquals("pr/7", result.checkoutBranch)
        assertNull(result.headBranch)
        // Without the fork's URL the merge ref is the only way to the head.
        val noUrl = provider(FakeTransport { adoPullRequest("forkSource" to mapOf("name" to "refs/heads/fix")) }).getPullRequest(7)
        assertEquals(listOf("+refs/heads/main:refs/smart-pr-review/7/base", "+refs/pull/7/merge:refs/smart-pr-review/7/merge"), noUrl.fetchRefspecs)
        assertNull(noUrl.headFetch)
    }

    @Test
    fun `replies to, resolves and reopens threads`() {
        val t = FakeTransport { emptyMap<String, Any>() }
        val ado = provider(t)
        ado.reply(adoPr, "5", "Done")
        ado.setThreadResolved(adoPr, "5", true)
        ado.setThreadResolved(adoPr, "5", false)
        assertTrue(t.calls[0].url.startsWith("$REPO_API/pullRequests/7/threads/5/comments?api-version=6.0"))
        assertEquals("POST", t.calls[0].method)
        assertEquals(JsonParser.parseString("""{"content":"Done","parentCommentId":1,"commentType":1}"""), t.body(0))
        assertEquals("PATCH", t.calls[1].method)
        assertEquals(JsonParser.parseString("""{"status":2}"""), t.body(1))
        assertEquals(JsonParser.parseString("""{"status":1}"""), t.body(2))
    }

    @Test
    fun `sends comment bodies without escaping html characters`() {
        val t = FakeTransport { emptyMap<String, Any>() }
        provider(t).reply(adoPr, "5", "a < b && c")
        assertEquals("""{"content":"a < b && c","parentCommentId":1,"commentType":1}""", t.calls[0].body)
    }

    @Test
    fun `keeps viewed files on this machine`() {
        val t = FakeTransport { null }
        val ado = provider(t)
        ado.setFileViewed(adoPr, "src/app.ts", true)
        ado.setFileViewed(adoPr, "src/other.ts", true)
        ado.setFileViewed(adoPr, "src/other.ts", false)
        assertEquals(listOf("src/app.ts"), ado.getViewedFiles(adoPr).toList())
        assertEquals(0, t.calls.size)
    }

    @Test
    fun `maps file threads tracked to the latest iteration and hides system and pull-request-level threads`() {
        fun text(content: String, id: Int = 1, vararg extra: Pair<String, Any?>) = mapOf(
            "id" to id, "content" to content, "publishedDate" to UPDATED, "author" to mapOf("displayName" to "Ana"), "commentType" to "text",
        ) + extra
        fun at(line: Int) = mapOf("line" to line, "offset" to 1)
        val first = "a".repeat(40)
        val threads = listOf(
            mapOf("id" to 1, "status" to "active", "threadContext" to mapOf("filePath" to "/src/app.ts", "rightFileStart" to at(4), "rightFileEnd" to at(6)), "comments" to listOf(text("Why?"))),
            mapOf("id" to 2, "comments" to listOf(text("Ana voted 10", 1, "commentType" to "system"))),
            mapOf("id" to 3, "status" to "active", "threadContext" to null, "comments" to listOf(text("Looks good overall"))),
            mapOf("id" to 4, "status" to "fixed", "threadContext" to mapOf("filePath" to "/src/old.ts", "leftFileStart" to at(2), "leftFileEnd" to at(2)), "comments" to listOf(text("Removed?"))),
            mapOf(
                "id" to 5, "status" to "active", "threadContext" to mapOf("filePath" to "/src/app.ts", "rightFileStart" to at(9), "rightFileEnd" to at(9)),
                "pullRequestThreadContext" to mapOf("iterationContext" to mapOf("secondComparingIteration" to 1)), "comments" to listOf(text("Old, file unchanged since")),
            ),
            mapOf(
                "id" to 6, "status" to "active", "threadContext" to mapOf("filePath" to "/src/app.ts", "rightFileStart" to at(12), "rightFileEnd" to at(12)),
                "pullRequestThreadContext" to mapOf("iterationContext" to mapOf("secondComparingIteration" to 1), "trackingCriteria" to mapOf("secondComparingIteration" to 3)),
                "comments" to listOf(text("Old but tracked"), text("deleted", 2, "isDeleted" to true)),
            ),
            mapOf(
                "id" to 7, "status" to "active", "threadContext" to mapOf("filePath" to "/src/app.ts", "rightFileStart" to at(2), "rightFileEnd" to at(3)),
                "pullRequestThreadContext" to mapOf(
                    "iterationContext" to mapOf("secondComparingIteration" to 1),
                    "trackingCriteria" to mapOf("secondComparingIteration" to 0, "origRightFileStart" to at(2), "origRightFileEnd" to at(3)),
                ),
                "comments" to listOf(text("Old and lost")),
            ),
            mapOf("id" to 8, "status" to "active", "threadContext" to mapOf("filePath" to "/src/app.ts"), "comments" to listOf(text("About this file"))),
        )
        val t = FakeTransport {
            if (it.url.contains("/iterations?")) {
                mapOf("value" to listOf(mapOf("id" to 1, "sourceRefCommit" to mapOf("commitId" to first)), mapOf("id" to 3, "sourceRefCommit" to mapOf("commitId" to ADO_HEAD))))
            } else {
                mapOf("value" to threads)
            }
        }
        val result = provider(t).getThreads(adoPr)
        assertTrue(t.calls[1].url.contains("/pullRequests/7/threads?\$iteration=3&\$baseIteration=0&api-version=6.0"))
        assertEquals(
            listOf(
                listOf("1", "src/app.ts", Side.HEAD, 4, 6, false, false, 1),
                listOf("4", "src/old.ts", Side.BASE, 2, 2, false, true, 1),
                listOf("5", "src/app.ts", Side.HEAD, 9, 9, false, false, 1),
                listOf("6", "src/app.ts", Side.HEAD, 12, 12, false, false, 1),
                listOf("7", "src/app.ts", Side.HEAD, null, null, true, false, 1),
                listOf("8", "src/app.ts", Side.HEAD, null, null, true, false, 1),
            ),
            result.map { listOf(it.id, it.path, it.side, it.startLine, it.line, it.isOutdated, it.isResolved, it.comments.size) },
        )
        assertEquals(listOf(null, null, null, null, Thread.Original(first, 3, 2), null), result.map { it.original })
    }
}
