package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.remote.ProviderKind

enum class PullRequestFilter { ASSIGNED, CREATED, ALL }

enum class Vote { COMMENT, APPROVE, APPROVE_WITH_SUGGESTIONS, REQUEST_CHANGES, WAIT_FOR_AUTHOR, REJECT }

data class PullRequestSummary(val id: Int, val title: String, val author: String, val updatedAt: String, val url: String)

data class PullRequest(
    val summary: PullRequestSummary,
    val body: String,
    val isDraft: Boolean,
    val baseSha: String,
    val headSha: String,
    /** Refspecs, fetched from the pull request remote, that make baseSha and headSha available locally. */
    val fetchRefspecs: List<String>,
    /** Head commits fetched from another repository instead (Azure DevOps forks). */
    val headFetch: HeadFetch? = null,
    /** Local branch created by "Checkout Pull Request": headBranch when there is one, else pr/<id>. */
    val checkoutBranch: String,
    /** The pull request's branch in this repository, which the checkout tracks; null for a fork's branch. */
    val headBranch: String? = null,
    /** Provider-specific id some APIs need (GitHub GraphQL node id). */
    val nodeId: String,
) {
    val id: Int get() = summary.id
    val title: String get() = summary.title
    val author: String get() = summary.author
    val url: String get() = summary.url

    data class HeadFetch(val url: String, val refspec: String)
}

data class ThreadComment(val id: String, val author: String, val body: String, val createdAt: String)

enum class Side { BASE, HEAD }

data class Thread(
    val id: String,
    val path: String,
    /** Diff side the thread is anchored to. */
    val side: Side,
    /** 1-based. Null when the thread is outdated. */
    val line: Int? = null,
    val startLine: Int? = null,
    val isOutdated: Boolean,
    /** Where an outdated head-side thread was written: the commit of the time and its 1-based lines there. */
    val original: Original? = null,
    val isResolved: Boolean,
    val comments: List<ThreadComment>,
) {
    data class Original(val sha: String, val line: Int, val startLine: Int? = null)
}

sealed interface DraftOrigin {
    data object Manual : DraftOrigin
    data class Ai(val profile: String, val engine: String, val severity: Severity) : DraftOrigin
    enum class Severity { ERROR, WARNING, INFO }
}

data class Draft(
    val id: String,
    val path: String,
    /** 1-based, head side. */
    val startLine: Int,
    val endLine: Int,
    /** 1-based columns of the text the reviewer selected (end exclusive); null when the draft is on whole lines. */
    val startColumn: Int? = null,
    val endColumn: Int? = null,
    val body: String,
    /** Pull request head when the draft was written. */
    val headSha: String,
    val origin: DraftOrigin,
)

/** Ids are draft ids, or "vote" / "summary" for the review-level parts a provider sends separately. */
data class PublishResult(val published: List<String>, val failed: List<Failure>) {
    data class Failure(val id: String, val error: String)
}

enum class CommentableLines { DIFF, FILE }

/** Blocking: every method runs on a background thread, never on the EDT. */
interface PullRequestProvider {
    val kind: ProviderKind
    val supportedVotes: List<Vote>
    /** DIFF: only lines inside diff hunks accept comments (GitHub). FILE: any head line (Azure DevOps). */
    val commentableLines: CommentableLines
    fun listPullRequests(filter: PullRequestFilter): List<PullRequestSummary>
    fun getPullRequest(id: Int): PullRequest
    fun getThreads(pr: PullRequest): List<Thread>
    fun reply(pr: PullRequest, threadId: String, body: String)
    fun setThreadResolved(pr: PullRequest, threadId: String, resolved: Boolean)
    fun publishReview(pr: PullRequest, body: String, drafts: List<Draft>, vote: Vote): PublishResult
    fun getViewedFiles(pr: PullRequest): Set<String>
    fun setFileViewed(pr: PullRequest, path: String, viewed: Boolean)
}
