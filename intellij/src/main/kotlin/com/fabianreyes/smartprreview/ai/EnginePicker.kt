package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.settings.SmartPrReviewSettings
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.SimpleListCellRenderer
import java.nio.file.Path

/** An engine of the settings, with the executable [findExecutable] found for it (null when missing). */
data class EngineChoice(val id: String, val command: String, val args: List<String>, val executable: Path?) {
    fun toEngine(): Engine? = executable?.let { Engine(id, it.toString(), args) }
}

/** Every configured engine with its executable, the default one first. Blocking (searches PATH): never call on the EDT. */
fun engineChoices(settings: SmartPrReviewSettings): List<EngineChoice> =
    settings.engines.map { (id, e) -> EngineChoice(id, e.command, e.args, findExecutable(e.command)) }
        .sortedBy { it.id != settings.defaultEngine }

/** Shows the popup of [choices] (the default one marked) and calls [onPicked] with the choice, or [onDismissed] when none was made. EDT. */
fun pickEngine(project: Project, choices: List<EngineChoice>, defaultId: String, onPicked: (EngineChoice) -> Unit, onDismissed: () -> Unit) {
    var picked = false
    JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
        .setTitle(message("ai.chooseEngine"))
        .setRenderer(object : SimpleListCellRenderer<EngineChoice>() {
            override fun customize(list: javax.swing.JList<out EngineChoice>, c: EngineChoice?, index: Int, selected: Boolean, hasFocus: Boolean) {
                c ?: return
                val star = if (c.id == defaultId) "★ " else ""
                val missing = if (c.executable == null) " · ${message("ai.notAvailable")}" else ""
                text = "$star${c.id} — ${c.command}$missing"
            }
        })
        .setItemChosenCallback { picked = true; onPicked(it) }
        .createPopup()
        .apply {
            addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (!picked && !event.isOk) onDismissed()
                }
            })
        }
        .showCenteredInCurrentWindow(project)
}

/** The engine is not installed: says so, with a link to its installation guide when there is one. EDT. */
fun reportMissing(project: Project, choice: EngineChoice) {
    val guide = installGuide(choice.id)
    val text = message("ai.notFound", choice.command)
    if (guide == null) {
        Messages.showErrorDialog(project, text, message("ai.reviewWithAi"))
    } else if (Messages.showDialog(project, text, message("ai.reviewWithAi"), arrayOf(message("ai.howToInstall"), message("action.cancel")), 0, Messages.getErrorIcon()) == 0) {
        BrowserUtil.browse(guide)
    }
}
