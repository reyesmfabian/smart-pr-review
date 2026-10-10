package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.remote.ProviderKind

/** In-memory PullRequestProvider (mirror of the VS Code FakeProvider): one pull request, #1 "Test PR" by ana. */
class FakeProvider : PullRequestProvider {
    override val kind = ProviderKind.GITHUB
    override val supportedVotes = listOf(Vote.COMMENT, Vote.APPROVE)
    override var commentableLines = CommentableLines.DIFF
    var threads: List<Thread> = emptyList()
    val viewed = mutableSetOf<String>()
    val published = mutableListOf<Triple<String, List<Draft>, Vote>>()
    /** Draft ids whose publication fails. */
    val failing = mutableSetOf<String>()
    /** When set, publishReview throws it. */
    var publishError: RuntimeException? = null
    var baseSha = "a".repeat(40)
    var headSha = "b".repeat(40)
    /** Test hooks: run at the start of getPullRequest (e.g. block on a latch); returned in the PullRequest; thrown by setFileViewed. */
    var onGetPullRequest: ((Int) -> Unit)? = null
    var headFetch: PullRequest.HeadFetch? = null
    var setFileViewedError: Throwable? = null
    var headBranch: String? = null

    val summary = PullRequestSummary(1, "Test PR", "ana", "", "https://example.com/pr/1")
    var list: (PullRequestFilter) -> List<PullRequestSummary> = { listOf(summary) }

    override fun listPullRequests(filter: PullRequestFilter) = list(filter)

    override fun getPullRequest(id: Int): PullRequest {
        onGetPullRequest?.invoke(id)
        return PullRequest(
        summary.copy(id = id), "Body", false, baseSha, headSha,
        listOf("+refs/heads/main:refs/smart-pr-review/1/base", "+refs/pull/1/head:refs/smart-pr-review/1/head"),
        checkoutBranch = headBranch ?: "pr/1", headBranch = headBranch, nodeId = "PR_1",
        headFetch = headFetch,
        )
    }

    override fun getThreads(pr: PullRequest) = threads
    override fun reply(pr: PullRequest, threadId: String, body: String) {}
    override fun setThreadResolved(pr: PullRequest, threadId: String, resolved: Boolean) {}
    override fun getViewedFiles(pr: PullRequest): Set<String> = viewed.toSet()
    override fun setFileViewed(pr: PullRequest, path: String, viewed: Boolean) {
        setFileViewedError?.let { throw it }
        if (viewed) this.viewed.add(path) else this.viewed.remove(path)
    }

    override fun publishReview(pr: PullRequest, body: String, drafts: List<Draft>, vote: Vote): PublishResult {
        publishError?.let { throw it }
        published += Triple(body, drafts, vote)
        return PublishResult(
            drafts.filter { it.id !in failing }.map { it.id },
            drafts.filter { it.id in failing }.map { PublishResult.Failure(it.id, "boom") },
        )
    }
}
