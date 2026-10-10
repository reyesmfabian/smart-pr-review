package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.SmartPrReviewRepository
import com.fabianreyes.smartprreview.http.HttpError
import com.fabianreyes.smartprreview.http.describeError
import com.fabianreyes.smartprreview.providers.PullRequestFilter
import com.fabianreyes.smartprreview.providers.PullRequestProvider
import com.fabianreyes.smartprreview.providers.PullRequestSummary
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import java.net.URI
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** The URL if it is http(s); a server-provided link must never open `javascript:` or `file:`. */
fun browserUrl(url: String): String? =
    runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }?.toString()

enum class ErrorAction { SIGN_IN, RETRY }

sealed interface PrNode {
    data class Group(val filter: PullRequestFilter) : PrNode
    data object Loading : PrNode
    data class Item(val pr: PullRequestSummary) : PrNode
    data class Error(val message: String, val action: ErrorAction) : PrNode
    data object Empty : PrNode
}

/**
 * The children of the three groups, loaded on demand. Free of Swing: `load` decides which thread runs the
 * listing (a pooled thread in production, the caller in tests) and `post` which thread publishes its outcome
 * (the EDT in production).
 */
class PullRequestTreeModel(
    private val provider: PullRequestProvider,
    private val load: (() -> Unit) -> Unit,
    private val post: (() -> Unit) -> Unit = { it() },
) {
    private val groups = HashMap<PullRequestFilter, List<PrNode>>() // EDT only
    private val generation = HashMap<PullRequestFilter, Int>() // EDT only: a slow, older load never overwrites a newer one
    private val listeners = mutableListOf<(PullRequestFilter) -> Unit>()

    fun children(filter: PullRequestFilter): List<PrNode> = groups[filter] ?: listOf(PrNode.Loading)

    fun onChange(listener: (PullRequestFilter) -> Unit) {
        listeners += listener
    }

    fun loadGroup(filter: PullRequestFilter) {
        val mine = (generation[filter] ?: 0) + 1
        generation[filter] = mine
        set(filter, listOf(PrNode.Loading))
        load {
            val result: List<PrNode> = try {
                provider.listPullRequests(filter).map(PrNode::Item).ifEmpty { listOf(PrNode.Empty) }
            } catch (e: Exception) {
                listOf(PrNode.Error(describeError(e), if (e is HttpError && e.status == 401) ErrorAction.SIGN_IN else ErrorAction.RETRY))
            }
            post { if (generation[filter] == mine) set(filter, result) }
        }
    }

    private fun set(filter: PullRequestFilter, nodes: List<PrNode>) {
        groups[filter] = nodes
        listeners.toList().forEach { it(filter) }
    }
}

/** The tool window's pull request list: three groups (Assigned to me, Created by me, All open). */
class PullRequestTreePanel(
    private val project: Project,
    private val ready: RepositoryState.Ready,
    // Seam for tests; production loads on a pooled thread and publishes on the EDT.
    private val model: PullRequestTreeModel = PullRequestTreeModel(
        ready.provider,
        { work -> ApplicationManager.getApplication().executeOnPooledThread { work() } },
        { ApplicationManager.getApplication().invokeLater(it, project.disposed) },
    ),
    /** Opens a pull request of the list; the tool window shows the progress in its Current Pull Request section. */
    private val onOpen: (Int) -> Unit = { openPullRequest(project, it) },
) : SimpleToolWindowPanel(true, true) {
    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root, true)
    internal val tree = Tree(treeModel)
    internal val groupNodes = PullRequestFilter.entries.associateWith { DefaultMutableTreeNode(PrNode.Group(it)) }
    private val requested = mutableSetOf<PullRequestFilter>()

    init {
        groupNodes.values.forEach { root.add(it) }
        treeModel.reload()
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()

        model.onChange { filter ->
            val node = groupNodes.getValue(filter)
            node.removeAllChildren()
            model.children(filter).forEach { node.add(DefaultMutableTreeNode(it, false)) }
            treeModel.nodeStructureChanged(node)
        }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                ((event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? PrNode.Group)?.let { request(it.filter) }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {}
        })
        // One click opens a pull request or follows an error's link; groups expand on one click, as in VS Code.
        installSingleClickOpen(tree) { path, pinned -> !pinned && activate(path) }

        val openInBrowser = object : AnAction(message("action.openInBrowser"), null, AllIcons.Nodes.PpWeb) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = selected() is PrNode.Item
            }

            override fun actionPerformed(e: AnActionEvent) {
                (selected() as? PrNode.Item)?.let { browserUrl(it.pr.url)?.let(BrowserUtil::browse) }
            }
        }
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(openInBrowser), "SmartPrReviewPullRequestsPopup")
        // Refresh and Search are on the tool window's title bar.
        setContent(ScrollPaneFactory.createScrollPane(tree))

        // ALL stays collapsed: it loads when the user expands it.
        listOf(PullRequestFilter.ASSIGNED, PullRequestFilter.CREATED).forEach { tree.expandPath(TreePath(groupNodes.getValue(it).path)) }
    }

    private fun request(filter: PullRequestFilter) {
        if (requested.add(filter)) model.loadGroup(filter)
    }

    /** Reloads the expanded groups; a collapsed one reloads when it is next expanded. */
    internal fun refresh() {
        requested.clear()
        groupNodes.forEach { (filter, node) -> if (tree.isExpanded(TreePath(node.path))) request(filter) }
    }

    private fun selected(): PrNode? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? PrNode

    internal fun activate(path: TreePath): Boolean {
        when (val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is PrNode.Item -> onOpen(node.pr.id)
            is PrNode.Error -> when (node.action) {
                ErrorAction.SIGN_IN -> {
                    ready.auth.signIn(force = true) // EDT: the click
                    SmartPrReviewRepository.getInstance(project).refresh()
                }
                ErrorAction.RETRY -> ((path.parentPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? PrNode.Group)?.let { model.loadGroup(it.filter) }
            }
            else -> return false
        }
        return true
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            toolTipText = null
            when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                is PrNode.Group -> append(
                    message(
                        when (node.filter) {
                            PullRequestFilter.ASSIGNED -> "list.group.assigned"
                            PullRequestFilter.CREATED -> "list.group.created"
                            PullRequestFilter.ALL -> "list.group.all"
                        },
                    ),
                )
                PrNode.Loading -> append(message("list.loading"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                PrNode.Empty -> append(message("list.empty"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                is PrNode.Item -> {
                    append("#${node.pr.id} ${node.pr.title}")
                    append("  ${node.pr.author}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = node.pr.url
                }
                is PrNode.Error -> {
                    append(node.message, SimpleTextAttributes.ERROR_ATTRIBUTES)
                    append("  " + message(if (node.action == ErrorAction.SIGN_IN) "action.signIn" else "action.retry"), SimpleTextAttributes.LINK_ATTRIBUTES)
                }
            }
        }
    }
}
