package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.SmartPrReviewRepository
import com.fabianreyes.smartprreview.providers.PullRequestFilter
import com.fabianreyes.smartprreview.providers.PullRequestSummary
import com.fabianreyes.smartprreview.ui.openPullRequest
import com.fabianreyes.smartprreview.ui.runWithRetry
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

data class SearchItem(val id: Int, val label: String, val description: String?, val typedNumber: Boolean = false)

private val NUMBER = Regex("^\\s*#?(\\d{1,9})\\s*$")

/**
 * The open pull requests matching [value] (number, title or author), plus "Open pull request #N" first when
 * the text is a number that is not listed (closed, or older than the list).
 */
fun searchItems(prs: List<PullRequestSummary>, value: String): List<SearchItem> {
    val needle = value.trim().removePrefix("#").lowercase()
    val items = prs
        .filter { needle.isEmpty() || "${it.id} ${it.title} ${it.author}".lowercase().contains(needle) }
        .map { SearchItem(it.id, "#${it.id} ${it.title}", it.author) }
    val typed = NUMBER.matchEntire(value)?.groupValues?.get(1)?.toInt() ?: return items
    if (prs.any { it.id == typed }) return items
    return listOf(SearchItem(typed, message("search.openNumber", typed.toString()), null, typedNumber = true)) + items
}

/** "Search Pull Requests": a popup filtered as you type; a number that is not listed opens anyway. */
class SearchPullRequestsAction : DumbAwareAction(message("search.title"), null, AllIcons.Actions.Find) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { SmartPrReviewRepository.getInstance(it).state } is RepositoryState.Ready
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ready = SmartPrReviewRepository.getInstance(project).state as? RepositoryState.Ready ?: return
        runWithRetry(project, message("search.title"), { ready.provider.listPullRequests(PullRequestFilter.ALL) }, done = { show(project, it) })
    }

    private fun show(project: Project, prs: List<PullRequestSummary>) {
        val field = SearchTextField(false)
        field.textEditor.emptyText.text = message("search.placeholder")
        val model = CollectionListModel(searchItems(prs, ""))
        val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectedIndex = 0
            cellRenderer = object : ColoredListCellRenderer<SearchItem>() {
                override fun customizeCellRenderer(list: JList<out SearchItem>, value: SearchItem, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(value.label)
                    value.description?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                }
            }
        }
        val panel = JPanel(BorderLayout()).apply {
            add(field, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
            preferredSize = Dimension(520, 320)
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, field.textEditor)
            .setTitle(message("search.title")).setRequestFocus(true).setResizable(true).setMovable(true).createPopup()
        fun choose() {
            val item = list.selectedValue ?: return
            popup.cancel()
            openPullRequest(project, item.id)
        }
        field.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                model.replaceAll(searchItems(prs, field.text))
                if (model.size > 0) list.selectedIndex = 0
            }
        })
        field.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> choose()
                    KeyEvent.VK_DOWN -> list.selectedIndex = minOf(list.selectedIndex + 1, model.size - 1)
                    KeyEvent.VK_UP -> list.selectedIndex = maxOf(list.selectedIndex - 1, 0)
                    else -> return
                }
                e.consume()
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) choose()
            }
        })
        popup.showCenteredInCurrentWindow(project)
    }
}
