package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.SmartPrReviewRepository
import com.fabianreyes.smartprreview.settings.SmartPrReviewProjectSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.IOException

/** "Create Review Profile": a template in the repository's profiles folder, opened for editing. */
class CreateProfileAction : DumbAwareAction(message("profile.create"), null, AllIcons.Actions.AddFile) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { SmartPrReviewRepository.getInstance(it).state } is RepositoryState.Ready
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = (SmartPrReviewRepository.getInstance(project).state as? RepositoryState.Ready)?.git?.root?.normalize() ?: return
        val folder = root.resolve(SmartPrReviewProjectSettings.getInstance(project).profilesPath).normalize()
        if (!isInsideRepository(root, folder)) {
            Messages.showErrorDialog(project, message("profile.outside"), message("profile.create"))
            return
        }
        val name = Messages.showInputDialog(project, message("profile.name"), message("profile.title"), null, "", object : InputValidatorEx {
            override fun getErrorText(inputString: String) = if (inputString.isBlank()) message("profile.nameRequired") else null
            override fun checkInput(inputString: String) = inputString.isNotBlank()
            override fun canClose(inputString: String) = checkInput(inputString)
        })?.trim()
        if (name.isNullOrEmpty()) return
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "profile" }
        val file = try {
            runWriteAction {
                val dir = VfsUtil.createDirectoryIfMissing(folder.toString()) ?: return@runWriteAction null
                dir.refresh(false, false)
                // An existing profile is opened, not overwritten.
                dir.findChild("$slug.md") ?: dir.createChildData(this, "$slug.md").also { it.setBinaryContent(profileTemplate(name).toByteArray()) }
            }
        } catch (e: IOException) {
            Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, message("profile.create"))
            return
        } ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }
}
