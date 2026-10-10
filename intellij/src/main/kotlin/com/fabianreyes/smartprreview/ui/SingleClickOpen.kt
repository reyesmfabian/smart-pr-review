package com.fabianreyes.smartprreview.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.ClickListener
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.TreePath

/**
 * Makes [tree] behave like VS Code's views. One click on a leaf, or Enter on it, calls [open] with pinned = false; the
 * second click of a double click calls it with pinned = true. One click or Enter on a node with children expands or
 * collapses it. The arrow keys only move the selection. A click on a CheckboxTree checkbox only toggles the box: the
 * tree's own listener, installed first, consumes that click. [open] returns whether it did something with the node.
 */
fun installSingleClickOpen(tree: JTree, open: (path: TreePath, pinned: Boolean) -> Boolean) {
    // Expanding on a click happens below; JTree's own double-click toggle would undo it.
    tree.toggleClickCount = 0
    object : ClickListener() {
        override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
            val path = tree.getPathForLocation(event.x, event.y) ?: return false
            return when {
                !tree.model.isLeaf(path.lastPathComponent) -> clickCount == 1 && toggle(tree, path)
                clickCount <= 2 -> open(path, clickCount == 2)
                else -> false
            }
        }
    }.installOn(tree)
    object : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            tree.selectionPath?.let { enter(tree, it, open) }
        }
    }.registerCustomShortcutSet(CommonShortcuts.ENTER, tree)
}

/** What Enter does on [path]: open a leaf, expand or collapse anything else. */
internal fun enter(tree: JTree, path: TreePath, open: (TreePath, Boolean) -> Boolean): Boolean =
    if (tree.model.isLeaf(path.lastPathComponent)) open(path, false) else toggle(tree, path)

private fun toggle(tree: JTree, path: TreePath): Boolean {
    if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
    return true
}
