package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.Thread

/** What the panel shows for an open pull request. */
data class CurrentPullRequestModel(
    val filesRoot: List<FileNode>,
    /** By path, then line. */
    val drafts: List<Draft>,
    /** Unresolved threads, by position. */
    val active: List<Thread>,
    val resolved: List<Thread>,
    val viewedCount: Int,
    val progressText: String,
)

fun currentPullRequestModel(c: OpenPullRequest): CurrentPullRequestModel {
    val viewed = c.files.count { it.path in c.viewed }
    val threads = byPosition(c.threads)
    return CurrentPullRequestModel(
        filesRoot = fileTree(c.files),
        drafts = c.drafts.all().sortedWith(compareBy<Draft, String>(String.CASE_INSENSITIVE_ORDER) { it.path }.thenBy { it.startLine }),
        active = threads.filter { !it.isResolved },
        resolved = threads.filter { it.isResolved },
        viewedCount = viewed,
        progressText = if (c.files.isNotEmpty() && viewed == c.files.size) message("tree.progressAll", c.files.size) else message("tree.progress", viewed, c.files.size),
    )
}

enum class Section { FILES, DRAFTS, CONVERSATIONS }

/** A node of the open pull request's tree. */
sealed interface ReviewNode {
    /** The same across rebuilds: what the panel keeps expanded and selected. */
    val key: String

    data class Header(val section: Section, val count: Int) : ReviewNode {
        override val key: String get() = "section:$section"
    }

    data class Folder(val path: String, val label: String) : ReviewNode {
        override val key: String get() = "folder:$path"
    }

    data class File(val file: ChangedFile, val viewed: Boolean) : ReviewNode {
        override val key: String get() = "file:${file.path}"
    }

    data class DraftItem(val draft: Draft) : ReviewNode {
        override val key: String get() = "draft:${draft.id}"
    }

    data class Conversations(val resolved: Boolean, val count: Int) : ReviewNode {
        override val key: String get() = if (resolved) "conversations:resolved" else "conversations:active"
    }

    data class ThreadItem(val thread: Thread) : ReviewNode {
        override val key: String get() = "thread:${thread.id}"
    }
}

/** A node, its children, and whether it starts expanded (later rebuilds keep what the user chose). */
data class ReviewTreeItem(val node: ReviewNode, val children: List<ReviewTreeItem> = emptyList(), val expanded: Boolean = false)

/**
 * The open pull request as VS Code shows it: Files (compact folders, open), Drafts (open) and Conversations with
 * Active (open) and Resolved (closed); a conversation group appears only when it has threads.
 */
fun reviewTree(c: OpenPullRequest): List<ReviewTreeItem> {
    val m = currentPullRequestModel(c)
    fun file(n: FileNode): ReviewTreeItem = when (n) {
        is FileNode.Folder -> ReviewTreeItem(ReviewNode.Folder(n.path, n.label), n.children.map { file(it) }, expanded = true)
        is FileNode.File -> ReviewTreeItem(ReviewNode.File(n.file, n.file.path in c.viewed))
    }
    val groups = listOf(false to m.active, true to m.resolved).filter { it.second.isNotEmpty() }.map { (resolved, threads) ->
        ReviewTreeItem(ReviewNode.Conversations(resolved, threads.size), threads.map { ReviewTreeItem(ReviewNode.ThreadItem(it)) }, expanded = !resolved)
    }
    return listOf(
        ReviewTreeItem(ReviewNode.Header(Section.FILES, c.files.size), m.filesRoot.map { file(it) }, expanded = true),
        ReviewTreeItem(ReviewNode.Header(Section.DRAFTS, m.drafts.size), m.drafts.map { ReviewTreeItem(ReviewNode.DraftItem(it)) }, expanded = true),
        ReviewTreeItem(ReviewNode.Header(Section.CONVERSATIONS, m.active.size + m.resolved.size), groups, expanded = true),
    )
}

/** The guidance line under the header: the next step for where the review stands. */
fun guidance(c: OpenPullRequest): String {
    val drafts = c.drafts.all().size
    val viewed = c.files.count { it.path in c.viewed }
    return when {
        drafts > 0 -> message("guide.drafts", drafts)
        c.files.isNotEmpty() && viewed == c.files.size -> message("guide.allViewed")
        viewed == 0 -> message("guide.start")
        else -> message("guide.markViewed")
    }
}
