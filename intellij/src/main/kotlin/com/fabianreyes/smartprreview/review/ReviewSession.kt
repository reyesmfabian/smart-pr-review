package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.git.LineRange
import com.fabianreyes.smartprreview.providers.CommentableLines
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.PublishResult
import com.fabianreyes.smartprreview.providers.PullRequest
import com.fabianreyes.smartprreview.providers.PullRequestProvider
import com.fabianreyes.smartprreview.providers.Thread
import com.fabianreyes.smartprreview.providers.Vote
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.fabianreyes.smartprreview.remote.RemoteInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

data class OpenPullRequest(
    val pr: PullRequest,
    val mergeBase: String,
    val files: List<ChangedFile>,
    var threads: List<Thread>,
    val viewed: MutableSet<String>,
    val drafts: DraftStore,
    /** Whether both the current branch and its HEAD match the pull request checkout. */
    var checkedOut: Boolean,
)

/** Identifies the repository in storage keys, e.g. "github:octo/app". */
internal fun repoKey(remote: RemoteInfo): String = when (remote.kind) {
    ProviderKind.GITHUB -> "github:${remote.owner}/${remote.repo}"
    ProviderKind.AZURE_DEVOPS -> "azureDevOps:${remote.host}/${remote.owner}/${remote.project}/${remote.repo}"
}

/** The drafts differ from the ones previewed; nothing was sent. */
class DraftsChangedException : RuntimeException()

private class Context(val git: Git, val remoteName: String, val provider: PullRequestProvider, val repoKey: String)

/**
 * The pull request being reviewed. Every method except require/current/onChange is blocking (git + network):
 * call it from a background thread. Listeners run on the EDT.
 */
@Service(Service.Level.PROJECT)
class ReviewSession(private val project: Project) : Disposable {
    @Volatile
    var current: OpenPullRequest? = null
        private set

    /** How listeners get called; tests replace it to run them synchronously. */
    @Volatile
    var notify: (() -> Unit) -> Unit = { ApplicationManager.getApplication().invokeLater(it, project.disposed) }

    /** Where drafts are persisted; tests replace it with an in-memory map. */
    @Volatile
    var draftStorage: KeyValueStore = PropertiesKeyValueStore(project)

    @Volatile
    private var context: Context? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Bumped by every open(): a slower, older open must not replace the pull request clicked last. */
    private val opening = AtomicInteger()

    fun onChange(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun fire() = notify { listeners.forEach { it() } }

    /**
     * Takes git, remote name and provider from the repository state. The state is recreated on every refresh
     * (and after signing in), so only a different repository closes the review.
     */
    fun attach(ready: RepositoryState.Ready) {
        val key = repoKey(ready.remote)
        if (context?.repoKey != key) close()
        context = Context(ready.git, ready.remoteName, ready.provider, key)
    }

    /** The provider of the attached state. */
    val provider: PullRequestProvider get() = ctx().provider

    /** The repository git of the attached state. */
    val git: Git get() = ctx().git

    private fun ctx(): Context = context ?: throw IllegalStateException(message("session.openFirst"))

    fun require(): OpenPullRequest = current ?: throw IllegalStateException(message("session.openFirst"))

    fun open(id: Int) {
        val c = ctx()
        val ticket = opening.incrementAndGet()
        val pr = c.provider.getPullRequest(id)
        c.git.fetchRefs(c.remoteName, pr.fetchRefspecs)
        pr.headFetch?.let { c.git.fetchRefs(safeRepositoryUrl(it.url), listOf(it.refspec)) }
        for (sha in listOf(pr.baseSha, pr.headSha)) {
            check(c.git.hasCommit(sha)) { message("session.commitMissing", sha.take(8)) }
        }
        val mergeBase = c.git.mergeBase(pr.baseSha, pr.headSha)
        val files = c.git.changedFiles(mergeBase, pr.headSha)
        val threads = c.provider.getThreads(pr)
        val viewed = c.provider.getViewedFiles(pr)
        val head = c.git.headSha()
        val branch = c.git.currentBranch()
        if (ticket != opening.get()) return
        val drafts = DraftStore(draftStorage, "${c.repoKey}:${pr.id}")
        current = OpenPullRequest(pr, mergeBase, files, threads, viewed.toCollection(java.util.concurrent.ConcurrentHashMap.newKeySet()), drafts, head == pr.headSha && branch == pr.checkoutBranch)
        fire()
    }

    /** Leaves the review: views and comments go away; drafts stay stored for the next open. */
    fun close() {
        // An open still in flight must not bring the pull request back.
        opening.incrementAndGet()
        current = null
        fire()
    }

    fun refresh() = open(require().pr.id)

    /** `confirmReset` is asked before discarding commits only the local branch has (after a force-push). */
    fun checkout(confirmReset: (String) -> Boolean) {
        val c = require()
        val x = ctx()
        check(!x.git.isDirty()) { message("session.dirty") }
        val branch = c.pr.checkoutBranch
        val headBranch = c.pr.headBranch
        val headSha = c.pr.headSha
        val reset = !x.git.canFastForward(branch, headSha)
        if (reset && !confirmReset(branch)) return
        if (headBranch != null) {
            // The remote-tracking branch to follow: open() fetched the pull request into refs/smart-pr-review only.
            x.git.fetchRefs(x.remoteName, listOf("+refs/heads/$headBranch:refs/remotes/${x.remoteName}/$headBranch"))
        }
        x.git.checkout(branch, headSha, reset, headBranch?.let { "${x.remoteName}/$it" })
        c.checkedOut = true
        fire()
    }

    /** Head-side lines of `path` that accept comments (1-based); null means every line. */
    fun commentableRanges(path: String): List<LineRange>? = commentableRanges(require(), path)

    /** [commentableRanges] for an explicit pull request, which need not be the open one any more. */
    fun commentableRanges(c: OpenPullRequest, path: String): List<LineRange>? {
        val x = ctx()
        val file = c.files.find { it.path == path }
        if (file == null || file.status == ChangedFile.Status.D) return emptyList()
        if (x.provider.commentableLines == CommentableLines.FILE) return null
        return x.git.diffRanges(c.mergeBase, c.pr.headSha, file)
    }

    fun reply(threadId: String, body: String) {
        ctx().provider.reply(require().pr, threadId, body)
        reloadThreads()
    }

    fun setResolved(threadId: String, resolved: Boolean) {
        ctx().provider.setThreadResolved(require().pr, threadId, resolved)
        reloadThreads()
    }

    fun setViewed(path: String, viewed: Boolean) {
        val c = require()
        try {
            ctx().provider.setFileViewed(c.pr, path, viewed)
            if (viewed) c.viewed.add(path) else c.viewed.remove(path)
        } finally {
            // Also on failure: the tree already toggled the checkbox and must show the real state again.
            fire()
        }
    }

    fun addDraft(
        path: String, startLine: Int, endLine: Int, body: String,
        origin: DraftOrigin = DraftOrigin.Manual, startColumn: Int? = null, endColumn: Int? = null,
    ): Draft = addDraftTo(require(), path, startLine, endLine, body, origin, startColumn, endColumn)

    /** Adds a draft to [c]'s own store, even when it is no longer the open pull request (never calls require()). */
    fun addDraftTo(
        c: OpenPullRequest, path: String, startLine: Int, endLine: Int, body: String,
        origin: DraftOrigin = DraftOrigin.Manual, startColumn: Int? = null, endColumn: Int? = null,
    ): Draft {
        val draft = c.drafts.add(Draft("", path, startLine, endLine, startColumn, endColumn, body, c.pr.headSha, origin))
        fire()
        return draft
    }

    /** Adds a section to the review summary, after what is already there. */
    fun appendSummary(text: String) = appendSummaryTo(require(), text)

    /** [appendSummary] on [c]'s own store (never calls require()). */
    fun appendSummaryTo(c: OpenPullRequest, text: String) {
        val drafts = c.drafts
        val existing = drafts.summary().trim()
        drafts.setSummary(if (existing.isNotEmpty()) "$existing\n\n$text" else text)
        fire()
    }

    fun updateDraft(id: String, body: String) {
        require().drafts.update(id, body)
        fire()
    }

    fun removeDrafts(ids: Collection<String>) {
        require().drafts.remove(ids)
        fire()
    }

    /**
     * Publishes every draft with the summary and vote. Returns null when the user declines to publish drafts
     * written against an older head. Drafts are removed only once the provider confirms them.
     * With [expectedRevision], throws [DraftsChangedException] (nothing sent) if the drafts are no longer
     * the ones the reviewer previewed.
     */
    fun publish(body: String, vote: Vote, expectedRevision: String? = null, confirmStale: (Int) -> Boolean): PublishResult? {
        val c = require()
        val provider = ctx().provider
        c.drafts.setSummary(body)
        val latest = provider.getPullRequest(c.pr.id)
        val stale = c.drafts.all().count { it.headSha != latest.headSha }
        if (stale > 0 && !confirmStale(stale)) return null
        // Read after the confirmation (which can take a while), then check: a draft added in between is caught,
        // one added later is simply not part of this publication.
        val drafts = c.drafts.all()
        if (expectedRevision != null && publishRevision(c) != expectedRevision) throw DraftsChangedException()
        val result = provider.publishReview(latest, body, drafts, vote)
        c.drafts.remove(result.published)
        if (result.failed.isEmpty()) c.drafts.setSummary("")
        reloadThreads()
        return result
    }

    override fun dispose() = listeners.clear()

    private fun reloadThreads() {
        val c = require()
        c.threads = ctx().provider.getThreads(c.pr)
        fire()
    }

    companion object {
        fun getInstance(project: Project): ReviewSession = project.service()
    }
}
