package com.fabianreyes.smartprreview.settings

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.SmartPrReviewRepository
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

/** Settings | Tools | Smart PR Review: machine-wide fields first, project fields in their own group. */
class SmartPrReviewConfigurable(private val project: Project) : BoundConfigurable(message("settings.displayName")) {
    override fun createPanel(): DialogPanel {
        val app = SmartPrReviewSettings.getInstance().state
        val proj = SmartPrReviewProjectSettings.getInstance(project).state
        return panel {
            row(message("settings.azureDevOpsHosts.label")) {
                textArea()
                    .bindText(
                        { app.azureDevOpsHosts.joinToString("\n") },
                        { app.azureDevOpsHosts = it.lines().map(String::trim).filter(String::isNotEmpty).toMutableList() },
                    )
                    .rows(3)
                    .align(AlignX.FILL)
                    .comment(message("settings.azureDevOpsHosts"))
            }
            row { checkBox(message("settings.allowApproval.label")).bindSelected(app::allowApproval).comment(message("settings.allowApproval")) }
            row(message("settings.defaultEngine.label")) { textField().bindText(app::defaultEngine).comment(message("settings.defaultEngine")) }
            row(message("settings.engines.label")) {
                textArea()
                    .bindText(app::enginesJson)
                    .rows(8)
                    .align(AlignX.FILL)
                    .comment(message("settings.engines"))
                    .validationOnInput { field ->
                        SmartPrReviewSettings.parseEngines(field.text).exceptionOrNull()?.let { error(message("settings.enginesInvalid", it.message ?: "")) }
                    }
            }
            row(message("settings.aiTimeoutMinutes.label")) { spinner(1..1440).bindIntValue(app::aiTimeoutMinutes).comment(message("settings.aiTimeoutMinutes")) }
            row { checkBox(message("settings.aiLabelComments.label")).bindSelected(app::aiLabelComments).comment(message("settings.aiLabelComments")) }
            group(message("settings.projectGroup")) {
                row(message("settings.profilesPath.label")) { textField().bindText(proj::profilesPath).align(AlignX.FILL).comment(message("settings.profilesPath")) }
                row(message("settings.aiLanguage.label")) { textField().bindText(proj::aiLanguage).align(AlignX.FILL).comment(message("settings.aiLanguage")) }
            }
        }
    }

    override fun apply() {
        super.apply()
        // Hosts decide whether the remote is supported; sign-in state may change with them.
        SmartPrReviewRepository.getInstance(project).refresh()
    }
}
