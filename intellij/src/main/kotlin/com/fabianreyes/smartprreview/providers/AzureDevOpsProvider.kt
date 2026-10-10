package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.http.*
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.fabianreyes.smartprreview.remote.RemoteInfo
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import java.net.URLEncoder

/** Supported by Azure DevOps Services and Azure DevOps Server 2020 and later. */
private const val API_VERSION = "6.0"

private val VOTES = mapOf(Vote.APPROVE to 10, Vote.APPROVE_WITH_SUGGESTIONS to 5, Vote.WAIT_FOR_AUTHOR to -5, Vote.REJECT to -10)
private val RESOLVED_STATUSES = setOf("fixed", "wontFix", "closed", "byDesign")

// Numeric CommentThreadStatus and CommentType values for request bodies.
private const val STATUS_ACTIVE = 1
private const val STATUS_FIXED = 2
private const val COMMENT_TEXT = 1

// URLEncoder is form-encoding: a space is "+", encodeURIComponent writes "%20".
private fun encode(segment: String) = URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

/** Azure DevOps has no API for "viewed" files: the state stays on this machine. */
interface ViewedFilesStore {
    fun get(key: String): Set<String>
    fun set(key: String, files: Set<String>)
}

class PropertiesViewedFilesStore(project: Project) : ViewedFilesStore {
    private val properties = PropertiesComponent.getInstance(project)
    override fun get(key: String): Set<String> = properties.getList(key).orEmpty().toSet()
    override fun set(key: String, files: Set<String>) = properties.setList(key, files.toList())
}

class AzureDevOpsProvider(
    remote: RemoteInfo,
    /** Authorization header value, see AzureDevOpsAuth. */
    private val authorization: () -> String,
    private val storage: ViewedFilesStore,
    private val transport: Transport = Http::send,
) : PullRequestProvider {
    override val kind = ProviderKind.AZURE_DEVOPS
    override val supportedVotes = listOf(Vote.COMMENT, Vote.APPROVE, Vote.APPROVE_WITH_SUGGESTIONS, Vote.WAIT_FOR_AUTHOR, Vote.REJECT)
    override val commentableLines = CommentableLines.FILE

    /** https://dev.azure.com/{organization} or {server origin}/{collection path} */
    private val collection = "${remote.origin}/" + remote.owner.split("/").joinToString("/", transform = ::encode)
    private val project = encode(remote.project ?: "")
    private val repoName = encode(remote.repo)
    private val repoApi = "$collection/$project/_apis/git/repositories/$repoName"
    private val webBase = "$collection/$project/_git/$repoName"
    private val teamsApi = "$collection/_apis/projects/$project/teams?\$mine=true&api-version=$API_VERSION"

    /** (Authorization, user id) of the last successful lookup. */
    @Volatile
    private var identity: Pair<String, String>? = null

    override fun listPullRequests(filter: PullRequestFilter): List<PullRequestSummary> {
        // Like the web's "Assigned to me": reviews requested from me or from one of my teams.
        val criteria = when (filter) {
            PullRequestFilter.ALL -> listOf("")
            PullRequestFilter.CREATED -> listOf("&searchCriteria.creatorId=${me()}")
            PullRequestFilter.ASSIGNED -> (listOf(me()) + myTeams()).map { "&searchCriteria.reviewerId=$it" }
        }
        // ponytail: first 100 per query, no pagination (spec §5.2). Queries run one after another.
        val pages = criteria.map { c -> repo("/pullrequests?searchCriteria.status=active$c&\$top=100") }
        val unique = LinkedHashMap<Int, JsonElement>()
        pages.flatMap { it.arr("value") }.forEach { unique[it.int("pullRequestId")!!] = it }
        // The API has no "last updated" date: newest first by creation (spec §5.2).
        return unique.values.sortedByDescending { it.str("creationDate") }.take(100).map(::summary)
    }

    /** Ids of my teams in this project; empty when the token cannot read teams. */
    private fun myTeams(): List<String> = try {
        call("GET", teamsApi).arr("value").mapNotNull { it.str("id") }
    } catch (e: Exception) {
        emptyList()
    }

    override fun getPullRequest(id: Int): PullRequest {
        val p = repo("/pullrequests/$id")
        val sourceCommit = p.obj("lastMergeSourceCommit").str("commitId")
        val targetCommit = p.obj("lastMergeTargetCommit").str("commitId")
        if (sourceCommit == null || targetCommit == null) throw RuntimeException(message("azure.preparing"))
        val sourceRef = p.str("sourceRefName")!!
        val base = "+${p.str("targetRefName")}:refs/smart-pr-review/$id/base"
        val fork = p.obj("forkSource")
        val forkUrl = fork.obj("repository").str("remoteUrl")?.takeIf { it.isNotEmpty() }
        val headBranch = if (fork != null) null else sourceRef.removePrefix("refs/heads/")
        return PullRequest(
            summary = summary(p),
            body = p.str("description") ?: "",
            isDraft = p.bool("isDraft") ?: false,
            baseSha = targetCommit,
            headSha = sourceCommit,
            // A fork's branch is not in this repository: fetch it from the fork (the merge ref is missing when the
            // pull request has conflicts), or from the merge ref when the fork's URL is unknown.
            fetchRefspecs = when {
                fork == null -> listOf(base, "+$sourceRef:refs/smart-pr-review/$id/head")
                forkUrl != null -> listOf(base)
                else -> listOf(base, "+refs/pull/$id/merge:refs/smart-pr-review/$id/merge")
            },
            headFetch = if (fork != null && forkUrl != null) PullRequest.HeadFetch(forkUrl, "+${fork.str("name")}:refs/smart-pr-review/$id/head") else null,
            checkoutBranch = headBranch ?: "pr/$id",
            headBranch = headBranch,
            nodeId = id.toString(),
        )
    }

    override fun getThreads(pr: PullRequest): List<Thread> {
        val iterations = iterations(pr.id)
        // Ask Azure DevOps to track every thread's position to the latest iteration.
        val result = repo("/pullRequests/${pr.id}/threads?\$iteration=${latestOf(iterations)}&\$baseIteration=0")
        return result.arr("value").flatMap { toThread(it, iterations) }
    }

    override fun reply(pr: PullRequest, threadId: String, body: String) {
        repo("/pullRequests/${pr.id}/threads/$threadId/comments", "POST", mapOf("content" to body, "parentCommentId" to 1, "commentType" to COMMENT_TEXT))
    }

    override fun setThreadResolved(pr: PullRequest, threadId: String, resolved: Boolean) {
        repo("/pullRequests/${pr.id}/threads/$threadId", "PATCH", mapOf("status" to if (resolved) STATUS_FIXED else STATUS_ACTIVE))
    }

    override fun getViewedFiles(pr: PullRequest): Set<String> = storage.get(viewedKey(pr))

    override fun setFileViewed(pr: PullRequest, path: String, viewed: Boolean) {
        val files = LinkedHashSet(getViewedFiles(pr))
        if (viewed) files.add(path) else files.remove(path)
        storage.set(viewedKey(pr), files)
    }

    /**
     * One thread per draft, then the vote, then the summary as a pull request comment. The vote and the summary go
     * only when every draft is in; a failed one is reported as "vote" or "summary" so a retry repeats only what is
     * missing (the vote is idempotent and is sent before the summary).
     */
    override fun publishReview(pr: PullRequest, body: String, drafts: List<Draft>, vote: Vote): PublishResult {
        val value = VOTES[vote]
        require(drafts.isNotEmpty() || body.isNotBlank() || value != null) { message("azure.nothingToPublish") }
        val published = mutableListOf<String>()
        val failed = mutableListOf<PublishResult.Failure>()
        fun attempt(id: String, label: String, action: () -> Unit) {
            try {
                action()
                published += id
            } catch (e: Exception) {
                failed += PublishResult.Failure(id, "$label — ${describeError(e)}")
            }
        }
        if (drafts.isNotEmpty()) {
            val iteration = latestOf(iterations(pr.id))
            val tracking = changeTrackingIds(pr.id, iteration)
            for (draft in drafts) {
                // Stop at the first failure (spec §11): the rest stay as drafts instead of failing one by one.
                if (failed.isNotEmpty()) break
                val changeTrackingId = tracking["/${draft.path}"]
                attempt(draft.id, "${draft.path}:${draft.startLine}") {
                    val context = linkedMapOf<String, Any?>(
                        "threadContext" to mapOf(
                            "filePath" to "/${draft.path}",
                            "rightFileStart" to mapOf("line" to draft.startLine, "offset" to (draft.startColumn ?: 1)),
                            "rightFileEnd" to mapOf("line" to draft.endLine, "offset" to (draft.endColumn ?: 1)),
                        ),
                    )
                    if (changeTrackingId != null) {
                        context["pullRequestThreadContext"] = mapOf(
                            "changeTrackingId" to changeTrackingId,
                            "iterationContext" to mapOf("firstComparingIteration" to 1, "secondComparingIteration" to iteration),
                        )
                    }
                    createThread(pr, draft.body, context)
                }
            }
        }
        if (failed.isEmpty() && value != null) {
            attempt("vote", message("azure.vote")) {
                val id = me()
                repo("/pullRequests/${pr.id}/reviewers/$id", "PUT", mapOf("vote" to value, "id" to id))
            }
        }
        if (failed.isEmpty() && body.isNotBlank()) {
            attempt("summary", message("azure.summary")) { createThread(pr, body, emptyMap()) }
        }
        return PublishResult(published, failed)
    }

    private fun createThread(pr: PullRequest, content: String, context: Map<String, Any?>) {
        repo(
            "/pullRequests/${pr.id}/threads", "POST",
            linkedMapOf<String, Any?>("comments" to listOf(mapOf("parentCommentId" to 0, "content" to content, "commentType" to COMMENT_TEXT)), "status" to STATUS_ACTIVE) + context,
        )
    }

    /** Repository path ("/src/a.ts") to changeTrackingId, which anchors new threads to the file across iterations. */
    private fun changeTrackingIds(id: Int, iteration: Int): Map<String, Int> {
        val ids = HashMap<String, Int>()
        var skip = 0
        do {
            val page = repo("/pullRequests/$id/iterations/$iteration/changes?\$top=2000&\$skip=$skip&\$compareTo=0")
            for (change in page.arr("changeEntries")) {
                val path = change.obj("item").str("path")
                val tracking = change.int("changeTrackingId")
                if (!path.isNullOrEmpty() && tracking != null) ids[path] = tracking
            }
            skip = page.int("nextSkip") ?: 0
        } while (skip > 0)
        return ids
    }

    /** Iteration id → its source commit (the pull request head at the time). */
    private fun iterations(id: Int): Map<Int, String?> =
        repo("/pullRequests/$id/iterations").arr("value").associate { it.int("id")!! to it.obj("sourceRefCommit").str("commitId") }

    /**
     * Id of the signed-in user (reviewer and creator filters, votes), cached per credential: a new token or
     * account means asking again. Not cached after a failure.
     */
    private fun me(): String {
        val auth = authorization()
        identity?.takeIf { it.first == auth }?.let { return it.second }
        val id = call("GET", "$collection/_apis/connectionData").obj("authenticatedUser").str("id")!!
        identity = auth to id
        return id
    }

    private fun repo(path: String, method: String = "GET", body: Any? = null): JsonElement {
        val url = "$repoApi$path${if (path.contains("?")) "&" else "?"}api-version=$API_VERSION"
        try {
            return call(method, url, body)
        } catch (e: HttpError) {
            if (e.status == 403) throw HttpError(403, "${e.message} ${message("azure.patScopeHint")}", e.headers)
            throw e
        }
    }

    private fun call(method: String, url: String, body: Any? = null): JsonElement =
        transport(HttpCall(method, url, headers(), body?.let(::toJson))) ?: JsonObject()

    private fun headers() = mapOf("Authorization" to authorization(), "Accept" to "application/json", "Content-Type" to "application/json")

    private fun summary(p: JsonElement) = PullRequestSummary(
        id = p.int("pullRequestId")!!,
        title = p.str("title")!!,
        author = p.obj("createdBy").str("displayName")!!,
        updatedAt = p.str("creationDate")!!,
        url = "$webBase/pullrequest/${p.int("pullRequestId")}",
    )

    private fun viewedKey(pr: PullRequest) = "viewed:$repoApi:${pr.id}"
}

private fun latestOf(iterations: Map<Int, *>): Int = maxOf(1, iterations.keys.maxOrNull() ?: 1)

private class Pos(val line: Int)

private fun pos(o: JsonObject?): Pos? = o?.int("line")?.let(::Pos)

/**
 * Maps a file thread; system messages and pull-request-level threads are not shown (like GitHub review bodies).
 * The positions come tracked to the requested iteration. A thread Azure DevOps could not track there (tracking
 * criteria without an iteration: "tracked if greater than 0") is outdated, and keeps its place in its own iteration.
 */
private fun toThread(t: JsonElement, iterations: Map<Int, String?>): List<Thread> {
    val comments = t.arr("comments").filter { it.bool("isDeleted") != true && it.str("commentType") != "system" && !it.str("content").isNullOrEmpty() }
    val context = t.obj("threadContext")
    val filePath = context.str("filePath")
    if (t.bool("isDeleted") == true || filePath.isNullOrEmpty() || comments.isEmpty()) return emptyList()
    val rightStart = pos(context.obj("rightFileStart"))
    val leftStart = pos(context.obj("leftFileStart"))
    val head = rightStart != null || leftStart == null
    val start = if (head) rightStart else leftStart
    val end = pos(context.obj(if (head) "rightFileEnd" else "leftFileEnd")) ?: start
    val tracking = t.obj("pullRequestThreadContext").obj("trackingCriteria")
    val lost = tracking != null && (tracking.int("secondComparingIteration") ?: 0) <= 0
    val outdated = start == null || lost
    val sha = iterations[t.obj("pullRequestThreadContext").obj("iterationContext").int("secondComparingIteration") ?: -1]
    val origStart = pos(tracking.obj("origRightFileStart")) ?: start
    val origEnd = pos(tracking.obj("origRightFileEnd")) ?: pos(tracking.obj("origRightFileStart")) ?: end
    val original = if (lost && head && !sha.isNullOrEmpty() && origStart != null && origEnd != null) Thread.Original(sha, origEnd.line, origStart.line) else null
    return listOf(
        Thread(
            id = t.str("id")!!,
            path = filePath.removePrefix("/"),
            side = if (head) Side.HEAD else Side.BASE,
            line = if (outdated) null else end?.line,
            startLine = if (outdated) null else start?.line,
            isOutdated = outdated,
            original = original,
            isResolved = t.str("status") in RESOLVED_STATUSES,
            comments = comments.map { ThreadComment(it.str("id")!!, it.obj("author").str("displayName")!!, it.str("content") ?: "", it.str("publishedDate")!!) },
        ),
    )
}
