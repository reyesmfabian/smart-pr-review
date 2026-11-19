package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.settings.SmartPrReviewSettings
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.SimpleListCellRenderer
import java.nio.file.Path
import com.fabianreyes.smartprreview.settings.Engine as EngineSettings

/** An engine of the settings, with the executable [findExecutable] found for it (null when missing). */
data class EngineChoice(val id: String, val settings: EngineSettings, val executable: Path?) {
    /** The engine to run, with [model] (when one was picked) appended to its arguments after the engine's model flag. */
    fun toEngine(model: String? = null): Engine? = executable?.let {
        val flag = settings.modelArg
        if (model == null || flag == null) Engine(id, it.toString(), settings.args) else Engine(id, it.toString(), settings.args + listOf(flag, model), model)
    }
}

/** Every configured engine with its executable, the default one first. Blocking (searches PATH): never call on the EDT. */
fun engineChoices(settings: SmartPrReviewSettings): List<EngineChoice> =
    settings.engines.map { (id, e) -> EngineChoice(id, e, findExecutable(e.command)) }
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
                text = "$star${c.id} — ${c.settings.command}$missing"
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

/** What "the CLI's own default" is remembered as: not a model name, so no model can collide with it. */
private const val CLI_DEFAULT = "-"

private fun lastModelKey(engineId: String) = "smartPrReview.cliModel.$engineId"

/**
 * What the model picker of a CLI engine offers, in order: the model picked last (the engine's default one
 * until then), the CLI's own default (null), then the engine's models and the ones the CLI listed.
 * [last] is not a model name when the CLI's own default was picked last.
 */
fun cliModelChoices(settings: EngineSettings, listed: List<CliModel>, last: String?): List<CliModel?> {
    val known = linkedMapOf<String, CliModel>()
    for (model in settings.models.map { CliModel(it) } + listed) {
        val seen = known[model.id]
        if (isModelName(model.id) && (seen == null || (seen.name == null && model.name != null))) known[model.id] = model
    }
    val preferred = (last ?: settings.defaultModel)?.takeIf(::isModelName)?.let { known[it] ?: CliModel(it) }
    return listOfNotNull(preferred) + listOf(null) + known.values.filter { it.id != preferred?.id }
}

/** A row of the model popup: a model, the CLI's own default (no model) or "Other…". */
private data class ModelRow(val model: CliModel?, val other: Boolean = false)

/**
 * Asks for the model of [choice], an engine that declares its model flag, offering [listed] (what the CLI printed)
 * next to the engine's own models. Calls [onPicked] with the model, or null for the CLI's own default, and
 * [onDismissed] when none was chosen. The choice is remembered and offered first next time. EDT.
 */
fun pickModel(project: Project, choice: EngineChoice, listed: List<CliModel>, onPicked: (String?) -> Unit, onDismissed: () -> Unit) {
    val properties = PropertiesComponent.getInstance()
    val key = lastModelKey(choice.id)
    val rows = cliModelChoices(choice.settings, listed, properties.getValue(key)).map { ModelRow(it) } + ModelRow(null, other = true)

    fun label(row: ModelRow): String = when {
        row.other -> message("ai.otherModel")
        row.model == null -> "${message("ai.defaultModel")} — ${message("ai.defaultModelHint", choice.settings.command)}"
        row.model.name != null -> "${row.model.id} — ${row.model.name}"
        else -> row.model.id
    }

    fun accept(model: String?) {
        properties.setValue(key, model ?: CLI_DEFAULT)
        onPicked(model)
    }

    var picked = false
    JBPopupFactory.getInstance().createPopupChooserBuilder(rows)
        .setTitle(message("ai.chooseModel", choice.id))
        .setRenderer(object : SimpleListCellRenderer<ModelRow>() {
            override fun customize(list: javax.swing.JList<out ModelRow>, row: ModelRow?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = row?.let(::label) ?: return
            }
        })
        // Typing filters the list: an account can have dozens of models.
        .setNamerForFiltering { label(it) }
        .setItemChosenCallback { row ->
            picked = true
            if (!row.other) return@setItemChosenCallback accept(row.model?.id)
            val typed = Messages.showInputDialog(
                project,
                message("ai.modelName", choice.settings.command, choice.settings.modelArg.orEmpty()),
                message("ai.reviewWithAi"),
                null,
                "",
                object : InputValidatorEx {
                    override fun getErrorText(inputString: String) = if (isModelName(inputString.trim())) null else message("ai.modelInvalid")
                    override fun checkInput(inputString: String) = isModelName(inputString.trim())
                    override fun canClose(inputString: String) = checkInput(inputString)
                },
            )?.trim()
            if (typed.isNullOrEmpty()) onDismissed() else accept(typed)
        }
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
    val text = message("ai.notFound", choice.settings.command)
    if (guide == null) {
        Messages.showErrorDialog(project, text, message("ai.reviewWithAi"))
    } else if (Messages.showDialog(project, text, message("ai.reviewWithAi"), arrayOf(message("ai.howToInstall"), message("action.cancel")), 0, Messages.getErrorIcon()) == 0) {
        BrowserUtil.browse(guide)
    }
}
