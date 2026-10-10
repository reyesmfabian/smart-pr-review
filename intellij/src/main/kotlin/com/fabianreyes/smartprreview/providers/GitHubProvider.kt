package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.http.*
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.google.gson.JsonElement
import java.net.URLEncoder

private const val API = "https://api.github.com"

private val THREADS_QUERY = """query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
  repository(owner: ${'$'}owner, name: ${'$'}name) {
    pullRequest(number: ${'$'}number) {
      reviewThreads(first: 100, after: ${'$'}after) {
        pageInfo { hasNextPage endCursor }
        nodes {
          id isResolved isOutdated path line startLine originalLine originalStartLine diffSide
          comments(first: 100) { nodes { id body createdAt author { login } originalCommit { oid } } }
        }
      }
    }
  }
}"""

private val FILES_QUERY = """query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
  repository(owner: ${'$'}owner, name: ${'$'}name) {
    pullRequest(number: ${'$'}number) {
      files(first: 100, after: ${'$'}after) {
        pageInfo { hasNextPage endCursor }
        nodes { path viewerViewedState }
      }
    }
  }
}"""

private val EVENTS = mapOf(Vote.COMMENT to "COMMENT", Vote.APPROVE to "APPROVE", Vote.REQUEST_CHANGES to "REQUEST_CHANGES")

/** The vote as the UI and the TypeScript extension name it: approveWithSuggestions, waitForAuthor... */
private fun voteName(vote: Vote): String =
    vote.name.lowercase().split('_').let { it.first() + it.drop(1).joinToString("") { p -> p.replaceFirstChar(Char::uppercase) } }

class GitHubProvider(
    private val owner: String,
    private val repo: String,
    /** The raw current token, without the "Bearer " prefix. */
    private val token: () -> String,
    private val transport: Transport = Http::send,
) : PullRequestProvider {
    override val kind = ProviderKind.GITHUB
    override val supportedVotes = listOf(Vote.COMMENT, Vote.APPROVE, Vote.REQUEST_CHANGES)
    override val commentableLines = CommentableLines.DIFF

    override fun listPullRequests(filter: PullRequestFilter): List<PullRequestSummary> {
        val qualifier = when (filter) {
            PullRequestFilter.ASSIGNED -> " review-requested:@me"
            PullRequestFilter.CREATED -> " author:@me"
            PullRequestFilter.ALL -> ""
        }
        // URLEncoder is form-encoding: a space is "+", encodeURIComponent writes "%20".
        val q = URLEncoder.encode("repo:$owner/$repo is:pr is:open$qualifier", Charsets.UTF_8).replace("+", "%20")
        // ponytail: first 100 per group, no pagination (spec §5.2)
        val result = rest("/search/issues?q=$q&sort=updated&order=desc&per_page=100")
        return result.arr("items").map(::summary)
    }

    override fun getPullRequest(id: Int): PullRequest {
        val p = rest("/repos/$owner/$repo/pulls/$id")
        val head = p.obj("head")
        val base = p.obj("base")
        // A fork's branch is not in this repository: only refs/pull/<id>/head has it.
        val headBranch = if (head.obj("repo").str("full_name") == base.obj("repo").str("full_name")) head.str("ref") else null
        return PullRequest(
            summary = summary(p),
            body = p.str("body") ?: "",
            isDraft = p.bool("draft")!!,
            baseSha = base.str("sha")!!,
            headSha = head.str("sha")!!,
            fetchRefspecs = listOf(
                "+refs/heads/${base.str("ref")}:refs/smart-pr-review/$id/base",
                "+refs/pull/$id/head:refs/smart-pr-review/$id/head",
            ),
            checkoutBranch = headBranch ?: "pr/$id",
            headBranch = headBranch,
            nodeId = p.str("node_id")!!,
        )
    }

    override fun getThreads(pr: PullRequest): List<Thread> =
        paginate(THREADS_QUERY, pr.id, "reviewThreads").map { t ->
            val line = t.int("line")
            val isOutdated = t.bool("isOutdated") == true || line == null
            // The commit the thread was written against: where an outdated head-side thread can still be shown.
            val comments = t.obj("comments").arr("nodes")
            val sha = comments.firstOrNull().obj("originalCommit").str("oid")
            val originalLine = t.int("originalLine")
            val originalStartLine = t.int("originalStartLine")
            val original = if (isOutdated && t.str("diffSide") == "RIGHT" && !sha.isNullOrEmpty() && originalLine != null && originalLine != 0) {
                Thread.Original(sha, originalLine, originalStartLine?.takeIf { it != 0 })
            } else {
                null
            }
            Thread(
                id = t.str("id")!!,
                path = t.str("path")!!,
                side = if (t.str("diffSide") == "LEFT") Side.BASE else Side.HEAD,
                line = line,
                startLine = t.int("startLine"),
                isOutdated = isOutdated,
                original = original,
                isResolved = t.bool("isResolved")!!,
                // ponytail: first 100 comments per thread; paginate comments if longer threads show up
                comments = comments.map { c ->
                    ThreadComment(c.str("id")!!, c.obj("author").str("login") ?: "ghost", c.str("body")!!, c.str("createdAt")!!)
                },
            )
        }

    override fun reply(pr: PullRequest, threadId: String, body: String) {
        graphql(
            "mutation(\$id: ID!, \$body: String!) { addPullRequestReviewThreadReply(input: { pullRequestReviewThreadId: \$id, body: \$body }) { comment { id } } }",
            linkedMapOf("id" to threadId, "body" to body),
        )
    }

    override fun setThreadResolved(pr: PullRequest, threadId: String, resolved: Boolean) {
        val mutation = if (resolved) "resolveReviewThread" else "unresolveReviewThread"
        graphql("mutation(\$id: ID!) { $mutation(input: { threadId: \$id }) { thread { id } } }", linkedMapOf("id" to threadId))
    }

    override fun getViewedFiles(pr: PullRequest): Set<String> =
        paginate(FILES_QUERY, pr.id, "files").filter { it.str("viewerViewedState") == "VIEWED" }.map { it.str("path")!! }.toSet()

    override fun setFileViewed(pr: PullRequest, path: String, viewed: Boolean) {
        val mutation = if (viewed) "markFileAsViewed" else "unmarkFileAsViewed"
        graphql(
            "mutation(\$id: ID!, \$path: String!) { $mutation(input: { pullRequestId: \$id, path: \$path }) { clientMutationId } }",
            linkedMapOf("id" to pr.nodeId, "path" to path),
        )
    }

    override fun publishReview(pr: PullRequest, body: String, drafts: List<Draft>, vote: Vote): PublishResult {
        val event = EVENTS[vote] ?: throw IllegalArgumentException(message("github.unsupportedVote", voteName(vote)))
        // GitHub requires the review body for COMMENT and REQUEST_CHANGES.
        if (body.isBlank() && (vote == Vote.REQUEST_CHANGES || vote == Vote.COMMENT)) {
            throw IllegalArgumentException(message("review.summaryRequired"))
        }
        val payload = linkedMapOf<String, Any?>("commit_id" to pr.headSha, "event" to event)
        if (body.isNotBlank()) payload["body"] = body
        payload["comments"] = drafts.map { d ->
            linkedMapOf<String, Any?>("path" to d.path, "body" to d.body, "side" to "RIGHT", "line" to d.endLine).also {
                if (d.startLine < d.endLine) {
                    it["start_line"] = d.startLine
                    it["start_side"] = "RIGHT"
                }
            }
        }
        rest("/repos/$owner/$repo/pulls/${pr.id}/reviews", "POST", toJson(payload))
        // GitHub creates the review atomically: every draft is published, or the call above throws.
        return PublishResult(drafts.map { it.id }, emptyList())
    }

    private fun paginate(query: String, number: Int, field: String): List<JsonElement> {
        val nodes = mutableListOf<JsonElement>()
        var after: String? = null
        do {
            // A null cursor is omitted from the JSON (Gson drops nulls); GraphQL treats it like an explicit null.
            val data = graphql(query, linkedMapOf("owner" to owner, "name" to repo, "number" to number, "after" to after))
            val page = data.obj("repository").obj("pullRequest").obj(field)
            nodes += page.arr("nodes")
            val info = page.obj("pageInfo")
            after = if (info.bool("hasNextPage") == true) info.str("endCursor") else null
        } while (after != null)
        return nodes
    }

    private fun rest(path: String, method: String = "GET", body: String? = null): JsonElement? =
        transport(HttpCall(method, "$API$path", headers(), body))

    private fun graphql(query: String, variables: Map<String, Any?>): JsonElement? {
        val result = rest("/graphql", "POST", toJson(linkedMapOf("query" to query, "variables" to variables)))
        val errors = result.arr("errors")
        if (errors.isNotEmpty()) throw IllegalStateException(errors.joinToString("; ") { it.str("message") ?: "" })
        return (result as? com.google.gson.JsonObject)?.get("data")?.takeUnless { it.isNullish }
    }

    private fun headers(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${token()}",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "Content-Type" to "application/json",
    )
}

private fun summary(item: JsonElement?): PullRequestSummary = PullRequestSummary(
    id = item.int("number")!!, title = item.str("title") ?: "", author = item.obj("user").str("login") ?: "ghost",
    updatedAt = item.str("updated_at") ?: "", url = item.str("html_url") ?: "",
)
