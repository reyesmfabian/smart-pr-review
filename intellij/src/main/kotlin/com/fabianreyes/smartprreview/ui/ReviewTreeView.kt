package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.review.OpenPullRequest
import com.fabianreyes.smartprreview.review.ReviewNode
import com.fabianreyes.smartprreview.review.ReviewTreeItem
import com.fabianreyes.smartprreview.review.Section
import com.fabianreyes.smartprreview.review.draftPlace
import com.fabianreyes.smartprreview.review.draftText
import com.fabianreyes.smartprreview.review.reviewTree
import com.fabianreyes.smartprreview.review.threadPlace
import com.fabianreyes.smartprreview.review.threadText
import com.intellij.icons.AllIcons
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.JBColor
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The open pull request as one tree: Files, Drafts and Conversations. A rebuild keeps what the user expanded,
 * collapsed and selected; a node seen for the first time (or that had no children) starts as its item says.
 * A checkbox click reports through [onViewed]; a rebuild never does.
 */
internal class ReviewTreeView(private val onViewed: (ChangedFile, Boolean) -> Unit) {
    private val root = CheckedTreeNode()
    val tree = CheckboxTree(Renderer(), root, CheckboxTreeBase.CheckPolicy(false, false, false, false))
    private var rebuilding = false
    private var shownPr: Int? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                val file = (node.userObject as? ReviewNode.File)?.file ?: return
                if (!rebuilding) onViewed(file, node.isChecked)
            }
        })
    }

    fun selected(): ReviewNode? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ReviewNode

    fun show(c: OpenPullRequest) {
        val samePr = shownPr == c.pr.id
        shownPr = c.pr.id
        val before = if (samePr) nodes() else emptyList()
        // A node that had no children could not be expanded: it gets its default, as a new one does.
        val known = before.filter { it.childCount > 0 }.map { key(it) }.toSet()
        val expanded = before.filter { tree.isExpanded(TreePath(it.path)) }.map { key(it) }.toSet()
        val selected = selected()?.key
        val defaults = HashMap<String, Boolean>()
        rebuilding = true
        try {
            root.removeAllChildren()
            reviewTree(c).forEach { root.add(node(it, defaults)) }
            (tree.model as DefaultTreeModel).reload()
        } finally {
            rebuilding = false
        }
        expand(root) { k -> k in expanded || (k !in known && defaults[k] == true) }
        // Only where it is still visible: selecting a node inside a closed group would open the group.
        nodes().find { key(it) == selected }?.let { TreePath(it.path) }?.takeIf { tree.isVisible(it) }?.let { tree.selectionPath = it }
    }

    /** Every node under the root, parents before children. */
    internal fun nodes(): List<DefaultMutableTreeNode> = buildList {
        fun walk(parent: DefaultMutableTreeNode) {
            for (child in parent.children()) {
                val n = child as DefaultMutableTreeNode
                add(n)
                walk(n)
            }
        }
        walk(root)
    }

    private fun key(n: DefaultMutableTreeNode) = (n.userObject as ReviewNode).key

    private fun node(item: ReviewTreeItem, defaults: MutableMap<String, Boolean>): DefaultMutableTreeNode {
        defaults[item.node.key] = item.expanded
        val n = when (val node = item.node) {
            is ReviewNode.File -> CheckedTreeNode(node).apply { isChecked = node.viewed }
            else -> DefaultMutableTreeNode(node)
        }
        item.children.forEach { n.add(node(it, defaults)) }
        return n
    }

    /** Expands, top down, the nodes [open] keeps open; the children of a closed node stay closed. */
    private fun expand(parent: DefaultMutableTreeNode, open: (String) -> Boolean) {
        for (child in parent.children()) {
            val n = child as DefaultMutableTreeNode
            if (n.childCount > 0 && open(key(n))) {
                tree.expandPath(TreePath(n.path))
                expand(n, open)
            }
        }
    }

    /** Plain text only: titles, paths and comment bodies are untrusted. */
    private class Renderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val r = textRenderer
            when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                is ReviewNode.Header -> r.append(
                    when (node.section) {
                        Section.FILES -> message("tree.files", node.count)
                        Section.DRAFTS -> message("tree.drafts", node.count)
                        Section.CONVERSATIONS -> message("tree.conversations")
                    },
                    SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES,
                )
                is ReviewNode.Folder -> {
                    r.icon = AllIcons.Nodes.Folder
                    r.append(node.label)
                }
                is ReviewNode.File -> {
                    val attributes = fileStatusAttributes(node.file.status)
                    r.append(node.file.path.substringAfterLast('/'), attributes)
                    val rename = node.file.oldPath?.let { " ← $it" }.orEmpty()
                    r.append("  ${node.file.status}$rename", attributes)
                }
                is ReviewNode.DraftItem -> {
                    val d = node.draft
                    r.icon = if (d.origin is DraftOrigin.Ai) AllIcons.Actions.IntentionBulb else AllIcons.Actions.Edit
                    r.append(draftPlace(d).substringAfterLast('/'))
                    r.append("  ${draftText(d)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is ReviewNode.Conversations -> r.append(message(if (node.resolved) "tree.resolvedComments" else "tree.activeComments", node.count))
                is ReviewNode.ThreadItem -> {
                    val t = node.thread
                    r.icon = if (t.isResolved) AllIcons.General.InspectionsOK else AllIcons.General.Balloon
                    r.append(threadPlace(t).substringAfterLast('/'))
                    r.append("  ${threadText(t)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }
}

/** Explicit red for deletions: the platform's deleted-file color is gray in several themes. */
internal fun fileStatusAttributes(status: ChangedFile.Status) = SimpleTextAttributes(
    SimpleTextAttributes.STYLE_PLAIN,
    when (status) {
        ChangedFile.Status.D -> JBColor(0xB42318, 0xF28B82)
        ChangedFile.Status.A -> JBColor(0x16743C, 0x81C995)
        ChangedFile.Status.M -> JBColor(0x245BB2, 0x8AB4F8)
        ChangedFile.Status.R -> JBColor(0x7851A9, 0xC6A0F6)
    },
)
