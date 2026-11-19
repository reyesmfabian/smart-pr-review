package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.auth.PasswordSafeSecrets
import com.fabianreyes.smartprreview.settings.SmartPrReviewSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory

/** "Set API Key of an AI Engine": kept in the PasswordSafe per URL, so it never reaches another server. An empty answer removes it. */
class SetEngineApiKeyAction : DumbAwareAction(message("action.setEngineApiKey")) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val urls = SmartPrReviewSettings.getInstance().engines.mapNotNull { (id, engine) -> engine.url?.let { id to it } }.toMap()
        if (urls.isEmpty()) return Messages.showInfoMessage(project, message("ai.noUrlEngines"), message("action.setEngineApiKey"))

        fun ask(id: String) {
            val url = urls.getValue(id)
            val value = Messages.showPasswordDialog(message("ai.apiKeyPrompt", url), message("ai.apiKeyTitle", id)) ?: return
            PasswordSafeSecrets.set(apiKeySecret(url), value.trim().ifEmpty { null })
        }

        if (urls.size == 1) return ask(urls.keys.single())
        JBPopupFactory.getInstance().createPopupChooserBuilder(urls.keys.toList())
            .setTitle(message("ai.chooseEngine"))
            .setItemChosenCallback(::ask)
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }
}
