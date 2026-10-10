package com.fabianreyes.smartprreview.ui.diff

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.ui.ReviewWindow
import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.Thread
import com.fabianreyes.smartprreview.review.OpenPullRequest
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.review.threadLines
import com.fabianreyes.smartprreview.review.threadPlacement
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.editor.SimpleDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.fabianreyes.smartprreview.providers.Side as ThreadSide

/** What a review diff shows; later tasks read it to tell review diffs apart and place comments. */
data class ReviewDiffInfo(val path: String, val leftSha: String, val rightSha: String, val file: ChangedFile)

val REVIEW_FILE: Key<ReviewDiffInfo> = Key.create("smartPrReview.reviewFile")

/** The thread or draft id whose popup the diff opens once it is on screen (VS Code shows that comment expanded). */
val REVIEW_FOCUS: Key<String> = Key.create("smartPrReview.reviewFocus")

/** The sha becomes a git argument ("sha:path"): only full hex ids, never an option or a ref like HEAD. */
val SHA = Regex("^[0-9a-f]{40,64}$", RegexOption.IGNORE_CASE)

fun revisionText(git: Git, sha: String, path: String): String = if (SHA.matches(sha)) git.showFile(sha, path) else ""

/**
 * Builds the read-only diff of [file]; blocking (runs git show), so not on the EDT.
 * [line] is 1-based and scrolled to on [side] (RIGHT = head); [leftSha] replaces the merge base (outdated threads);
 * [focus] is the thread or draft whose popup opens with the diff.
 */
fun diffRequest(
    project: Project, c: OpenPullRequest, git: Git, file: ChangedFile, line: Int? = null, prId: Int,
    side: Side = Side.RIGHT, leftSha: String = c.mergeBase, focus: String? = null,
): SimpleDiffRequest {
    val factory = DiffContentFactory.getInstance()
    val type = FileTypeManager.getInstance().getFileTypeByFileName(file.path.substringAfterLast('/'))
    val head = c.pr.headSha
    // EmptyContent selects a one-sided viewer, losing deletion highlighting and our two-sided comment gutter.
    val left = factory.create(project, if (file.status == ChangedFile.Status.A) "" else revisionText(git, leftSha, file.oldPath ?: file.path), type)
    val right = factory.create(project, if (file.status == ChangedFile.Status.D) "" else revisionText(git, head, file.path), type)
    return SimpleDiffRequest("${file.path.substringAfterLast('/')} (#$prId)", left, right, message("diff.before", file.oldPath ?: file.path, leftSha.take(8)), message("diff.after", file.path, head.take(8))).apply {
        val info = ReviewDiffInfo(file.path, leftSha, head, file)
        putUserData(REVIEW_FILE, info)
        putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        putUserData(DiffUserDataKeys.PLACE, "SmartPrReview")
        putUserData(com.intellij.diff.util.DiffUserDataKeysEx.FORCE_DIFF_TOOL, com.intellij.diff.tools.simple.SimpleDiffTool.INSTANCE)
        if (line == null) putUserData(com.intellij.diff.util.DiffUserDataKeysEx.SCROLL_TO_CHANGE, com.intellij.diff.util.DiffUserDataKeysEx.ScrollToPolicy.FIRST_CHANGE)
        if (line != null) putUserData(DiffUserDataKeys.SCROLL_TO_LINE, com.intellij.openapi.util.Pair(side, line - 1))
        if (focus != null) putUserData(REVIEW_FOCUS, focus)
    }
}

/** What the preview tab knows a diff by: the same file, line and comment is the same diff. */
internal fun diffKey(path: String, line: Int?, focus: String?, revision: String = "") = "$revision:$path:${line ?: ""}:${focus ?: ""}"

private fun revisionKey(c: OpenPullRequest, leftSha: String = c.mergeBase, side: Side = Side.RIGHT) = "${c.pr.id}:$leftSha:${c.pr.headSha}:$side"

private fun show(project: Project, session: ReviewSession, file: ChangedFile, line: Int?, side: Side, leftSha: String?, focus: String?) {
    val c = session.require()
    val git = session.git
    // Over a modal dialog (Prepare Review) an editor tab would open hidden behind it: the diff window is used there.
    val modal = ModalityState.current() != ModalityState.nonModal()
    // A file already in a tab is only shown again (a reload would open a second tab under a double click). A draft or
    // a thread always reloads: opening it means opening its popup, which happens when the diff is created.
    val key = diffKey(file.path, line, focus, revisionKey(c, leftSha ?: c.mergeBase, side))
    if (!modal && !ReviewWindow.getInstance(project).isOpen && focus == null && DiffPreviewTab.getInstance(project).reveal(key)) return
    val ticket = DiffPreviewTab.getInstance(project).ticket()
    object : Task.Backgroundable(project, file.path, false) {
        private var request: SimpleDiffRequest? = null
        override fun run(indicator: ProgressIndicator) {
            request = diffRequest(project, c, git, file, line, c.pr.id, side, leftSha ?: c.mergeBase, focus)
        }

        // Runs on the EDT.
        override fun onSuccess() {
            val r = request ?: return
            if (session.current !== c) return
            if (modal) DiffManager.getInstance().showDiff(project, r)
            else if (!ReviewWindow.getInstance(project).showDiff(r, ticket)) DiffPreviewTab.getInstance(project).show(key, SimpleDiffVirtualFile(r), ticket)
        }
    }.queue()
}

/**
 * [file]'s diff in the preview tab, scrolled to [line] (1-based, head side), with [focus]'s popup open when given.
 * [pinned] is the second click of a double click: it keeps that tab open instead of loading the diff again.
 */
fun openFileDiff(project: Project, session: ReviewSession, file: ChangedFile, line: Int? = null, focus: String? = null, pinned: Boolean = false) {
    if (pinned) return DiffPreviewTab.getInstance(project).pin(diffKey(file.path, line, focus, revisionKey(session.require())))
    show(project, session, file, line, Side.RIGHT, null, focus)
}

/** The draft's diff at its line, with the draft open for editing. */
fun openDraft(project: Project, session: ReviewSession, draft: Draft) {
    val file = session.current?.files?.find { it.path == draft.path } ?: return
    openFileDiff(project, session, file, draft.startLine, draft.id)
}

/** The thread's diff at its lines, with the conversation open. */
fun openThread(project: Project, session: ReviewSession, thread: Thread) {
    val c = session.require()
    val file = c.files.find { it.path == thread.path } ?: ChangedFile(ChangedFile.Status.M, thread.path)
    val line = threadLines(thread)?.first ?: return openFileDiff(project, session, file)
    val placement = threadPlacement(c, thread)
    when {
        placement.side == ThreadSide.BASE -> show(project, session, file, line, Side.LEFT, null, thread.id)
        placement.sha == c.pr.headSha -> openFileDiff(project, session, file, line, thread.id)
        // Outdated: the lines belong to the commit the thread was written on, shown against the current head.
        else -> show(project, session, file.copy(status = ChangedFile.Status.M, oldPath = null), line, Side.LEFT, placement.sha, thread.id)
    }
}
