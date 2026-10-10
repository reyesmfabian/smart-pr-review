package com.fabianreyes.smartprreview.ui.diff

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.git.LineRange
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.review.OpenPullRequest
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.review.SelectedRange
import com.fabianreyes.smartprreview.review.selectedRange
import com.fabianreyes.smartprreview.review.suggestionBlock
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import javax.swing.Icon

/** The head (right) editor of a two-side diff viewer when [editor] is it; null for any other editor or viewer (e.g. unified). */
internal fun headEditor(viewer: Any?, editor: Editor?): Editor? =
    if (viewer is TwosideTextDiffViewer && editor != null && editor === viewer.editor2) editor else null

private fun editorOf(e: AnActionEvent): Editor? = headEditor(e.getData(DiffDataKeys.DIFF_VIEWER), e.getData(CommonDataKeys.EDITOR))

/** The editor's selection (or its caret line) as drafts store it. */
internal fun selection(editor: Editor): SelectedRange {
    val doc = editor.document
    val s = editor.selectionModel
    val start = if (s.hasSelection()) s.selectionStart else editor.caretModel.offset
    val end = if (s.hasSelection()) s.selectionEnd else start
    val sl = doc.getLineNumber(start)
    val el = doc.getLineNumber(end)
    return selectedRange(sl, start - doc.getLineStartOffset(sl), el, end - doc.getLineStartOffset(el)) {
        doc.getLineEndOffset(it) - doc.getLineStartOffset(it)
    }
}

/** Whether lines [start]..[end] (1-based) fall inside one commentable range; null ranges mean every line. */
internal fun lineAllowed(ranges: List<LineRange>?, start: Int, end: Int): Boolean =
    ranges == null || ranges.any { start >= it.start && end <= it.end }

/** The pull request a draft on [info]'s diff belongs to: the open one, only while it is the version the diff shows. */
internal fun draftTarget(current: OpenPullRequest?, info: ReviewDiffInfo): OpenPullRequest? =
    current?.takeIf { it.pr.headSha == info.rightSha }

/**
 * Adds a draft on lines [start]..[end] (1-based) of [info]'s file. The diff check is blocking, so it runs off the EDT
 * together with [create], which gets the pull request to write to; [done] gets the draft on the EDT. When another pull
 * request or new commits replaced the one the diff shows, or the lines are outside the diff, a warning is shown; then,
 * and when [create] throws, [failed] runs.
 */
internal fun addDraftInDiff(
    project: Project, info: ReviewDiffInfo, title: String, hint: String, start: Int, end: Int,
    create: (ReviewSession, OpenPullRequest) -> Draft, done: (Draft) -> Unit, failed: () -> Unit = {},
) {
    val session = ReviewSession.getInstance(project)
    var draft: Draft? = null
    var stale = false
    runInBackground(project, title, {
        // Captured once: a pull request opened meanwhile must not receive this diff's draft.
        val c = draftTarget(session.current, info)
        if (c == null) stale = true
        else if (lineAllowed(session.commentableRanges(c, info.path), start, end)) draft = create(session, c)
    }, onError = failed, done = {
        val d = draft
        if (d != null) done(d) else {
            failed()
            Messages.showWarningDialog(project, if (stale) message("draft.stale") else hint, title)
        }
    })
}

/** The diff data is on the request, so these actions work from the diff's editor context menu. */
abstract class ReviewEditorAction(text: String, icon: Icon) : AnAction(text, null, icon) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    protected fun info(e: AnActionEvent): ReviewDiffInfo? = e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(REVIEW_FILE)

    /** The head editor, only while the open pull request is the one this diff was created for. */
    protected fun target(e: AnActionEvent): Editor? {
        val project = e.project ?: return null
        val info = info(e) ?: return null
        if (info.file.status == ChangedFile.Status.D) return null
        if (ReviewSession.getInstance(project).current?.pr?.headSha != info.rightSha) return null
        return editorOf(e)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = target(e) != null
    }

    protected fun addInDiff(
        e: AnActionEvent, title: String, hint: String, start: Int, end: Int,
        create: (ReviewSession, OpenPullRequest, ReviewDiffInfo) -> Draft, done: (Draft) -> Unit,
    ) {
        val project = e.project ?: return
        val info = info(e) ?: return
        addDraftInDiff(project, info, title, hint, start, end, { session, c -> create(session, c, info) }, done)
    }
}

class AddDraftAction : ReviewEditorAction(message("action.addDraft"), AllIcons.General.Add) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = target(e) ?: return
        val range = selection(editor)
        val info = info(e) ?: return
        showCommentEditor(project, editor, info, range)
    }
}

class AddSuggestionAction : ReviewEditorAction(message("action.addSuggestion"), AllIcons.Actions.IntentionBulb) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = target(e) ?: return
        val range = selection(editor)
        val doc = editor.document
        val code = doc.getText(TextRange(doc.getLineStartOffset(range.startLine), doc.getLineEndOffset(range.endLine)))
        addInDiff(e, message("action.addSuggestion"), message("draft.suggestionHint"), range.startLine + 1, range.endLine + 1, { session, c, info ->
            session.addDraftTo(c, info.path, range.startLine + 1, range.endLine + 1, suggestionBlock(code), DraftOrigin.Manual)
        }, done = { showDraftPopup(project, ReviewSession.getInstance(project), it, editor, it.endLine - 1) })
    }
}
