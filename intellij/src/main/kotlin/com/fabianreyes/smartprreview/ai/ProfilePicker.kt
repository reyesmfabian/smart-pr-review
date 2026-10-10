package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Checkbox per profile; the ones that apply to a changed file come checked. */
class ProfilePicker(project: Project, private val profiles: List<Profile>, changedPaths: List<String>) : DialogWrapper(project) {
    private val applies = profiles.map { p -> changedPaths.any { appliesTo(p.include, it) } }
    private val boxes = profiles.mapIndexed { i, p ->
        val origin = when {
            p.source == Profile.Source.BUNDLED -> message("ai.bundled")
            p.local -> message("ai.local")
            else -> ""
        }
        val text = listOf(p.name, p.description, origin.takeIf { it.isNotEmpty() }?.let { "($it)" }).filter { !it.isNullOrEmpty() }.joinToString(" · ")
        JBCheckBox(text, applies[i]).apply { putClientProperty("html.disable", true) }
    }

    init {
        title = message("ai.reviewWithAi")
        init()
    }

    val selected: List<Profile> get() = profiles.filterIndexed { i, _ -> boxes[i].isSelected }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply { gridx = 0; anchor = GridBagConstraints.WEST; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL }
        panel.add(JBLabel(message("ai.chooseProfiles")), c)
        boxes.forEachIndexed { i, box ->
            panel.add(box, c)
            if (!applies[i]) panel.add(JBLabel(message("ai.noMatch")).apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyLeft(24)
            }, c)
        }
        return panel
    }
}
