package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.Side
import com.fabianreyes.smartprreview.providers.Thread
import com.fabianreyes.smartprreview.ui.diff.ReviewDiffInfo
import com.intellij.diff.util.Side as DiffSide

private val URL_SCHEME = Regex("^(https?|ssh|file)://", RegexOption.IGNORE_CASE)
private val SCP_LIKE = Regex("^[\\w.-]+@[\\w.-]+:[^-]")

/** The URL becomes a git argument: only http(s), ssh, file and user@host:path URLs; never an option like "--upload-pack=…". */
fun safeRepositoryUrl(url: String): String {
    if (URL_SCHEME.containsMatchIn(url) || SCP_LIKE.containsMatchIn(url)) return url
    throw IllegalArgumentException(message("session.unsupportedUrl", url))
}

/** The lines a thread covers (end line, start line), 1-based, in the file version it is shown on; null when it has none. */
fun threadLines(t: Thread): Pair<Int, Int?>? =
    if (t.isOutdated) t.original?.let { it.line to it.startLine } else t.line?.let { it to t.startLine }

/** Where a thread is shown: the diff side, and the commit and path of the file version holding its lines. */
data class Placement(val side: Side, val sha: String, val path: String)

fun threadPlacement(c: OpenPullRequest, t: Thread): Placement {
    val original = t.original
    if (t.isOutdated && original != null) return Placement(Side.HEAD, original.sha, t.path)
    if (t.side == Side.BASE) {
        val oldPath = c.files.find { it.path == t.path && it.oldPath != null }?.oldPath
        return Placement(Side.BASE, c.mergeBase, oldPath ?: t.path)
    }
    return Placement(Side.HEAD, c.pr.headSha, t.path)
}

/** A suggestion block; the fence outgrows any backtick run in the code so it cannot close early. */
fun suggestionBlock(code: String): String {
    val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(code).maxOfOrNull { it.value.length } ?: 0) + 1))
    return "${fence}suggestion\n$code\n$fence"
}

/** A suggestion draft's body: the reviewer's comment, when there is one, then the suggestion block. */
fun suggestionBody(comment: String, code: String): String =
    listOf(comment.trim(), suggestionBlock(code)).filter { it.isNotEmpty() }.joinToString("\n\n")

enum class MarkKind { THREAD_ACTIVE, THREAD_RESOLVED, DRAFT_MANUAL, DRAFT_AI }

/** A gutter icon in a review diff; [id] is the thread id or the draft id. */
data class GutterMark(val side: DiffSide, val line0: Int, val kind: MarkKind, val id: String)

/** The threads and drafts a diff viewer shows, by side and 0-based line. */
fun gutterMarks(c: OpenPullRequest, info: ReviewDiffInfo): List<GutterMark> {
    val marks = mutableListOf<GutterMark>()
    for (t in c.threads) {
        val p = threadPlacement(c, t)
        val line = threadLines(t)?.first ?: continue
        // A base thread on a renamed file is placed on the old path.
        val side = when {
            p.side == Side.BASE -> if (p.sha == info.leftSha && p.path == (info.file.oldPath ?: info.path)) DiffSide.LEFT else null
            p.path != info.path -> null
            p.sha == info.rightSha -> DiffSide.RIGHT
            p.sha == info.leftSha -> DiffSide.LEFT
            else -> null
        } ?: continue
        marks += GutterMark(side, line - 1, if (t.isResolved) MarkKind.THREAD_RESOLVED else MarkKind.THREAD_ACTIVE, t.id)
    }
    if (info.rightSha == c.pr.headSha) {
        for (d in c.drafts.all()) {
            if (d.path != info.path) continue
            marks += GutterMark(DiffSide.RIGHT, d.endLine - 1, if (d.origin is DraftOrigin.Ai) MarkKind.DRAFT_AI else MarkKind.DRAFT_MANUAL, d.id)
        }
    }
    return marks.sortedWith(compareBy({ it.side }, { it.line0 }))
}
