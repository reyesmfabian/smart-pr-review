package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.review.OpenPullRequest
import com.fabianreyes.smartprreview.review.ReviewSession
import com.fabianreyes.smartprreview.settings.SmartPrReviewProjectSettings
import com.fabianreyes.smartprreview.settings.SmartPrReviewSettings
import com.fabianreyes.smartprreview.ui.runWithRetry
import com.intellij.DynamicBundle
import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

private val LOG = logger<AiReviewAction>()

/** "Review with AI": pick profiles, pick an engine, review in the background, then report. One review at a time per project. */
class AiReviewAction : DumbAwareAction(message("ai.reviewWithAi"), null, AllIcons.Actions.Lightning) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { ReviewSession.getInstance(it).current } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!AiReviewGuard.getInstance(project).tryAcquire()) {
            Messages.showInfoMessage(project, message("ai.busy"), message("ai.reviewWithAi"))
            return
        }
        // From here on, every way out must call finish().
        val session = ReviewSession.getInstance(project)
        val c = session.current
        if (c == null) return finish(project)
        val folder = repositorySafe(SmartPrReviewProjectSettings.getInstance(project).profilesPath, c.files, DEFAULT_PROFILES_FOLDER)
        var profiles = emptyList<Profile>()
        object : Task.Backgroundable(project, message("ai.reviewWithAi"), false) {
            override fun run(indicator: ProgressIndicator) {
                profiles = loadProfiles(ProfileSources(session.git, folder, c.mergeBase, c.files, ::bundledProfiles))
            }

            override fun onSuccess() = stage(project) { chooseProfiles(project, session, c, profiles) }
            override fun onThrowable(error: Throwable) {
                finish(project)
                fail(project, error)
            }
        }.queue()
    }

    private fun chooseProfiles(project: Project, session: ReviewSession, c: OpenPullRequest, profiles: List<Profile>) {
        val changed = c.files.filter { it.status != com.fabianreyes.smartprreview.git.ChangedFile.Status.D }.map { it.path }
        if (profiles.isEmpty()) {
            notify(project, NotificationType.WARNING, message("ai.noProfiles"))
            return finish(project)
        }
        val dialog = ProfilePicker(project, profiles, changed)
        val selected = if (dialog.showAndGet()) dialog.selected else emptyList()
        if (selected.isEmpty()) return finish(project)
        val settings = SmartPrReviewSettings.getInstance()
        var choices = emptyList<EngineChoice>()
        object : Task.Backgroundable(project, message("ai.reviewWithAi"), false) {
            override fun run(indicator: ProgressIndicator) {
                choices = engineChoices(settings)
            }

            override fun onSuccess() = stage(project) {
                pickEngine(
                    project, choices, settings.defaultEngine,
                    // The review task takes over the guard and releases it when it finishes.
                    onPicked = { choice ->
                        stage(project) {
                            val engine = choice.toEngine()
                            if (engine == null) {
                                finish(project)
                                reportMissing(project, choice)
                            } else review(project, session, c, selected, engine)
                        }
                    },
                    onDismissed = { finish(project) },
                )
            }

            override fun onThrowable(error: Throwable) {
                finish(project)
                fail(project, error)
            }
        }.queue()
    }

    private fun review(project: Project, session: ReviewSession, c: OpenPullRequest, profiles: List<Profile>, engine: Engine) {
        // The pull request the profiles were loaded for must still be the open one (the dialogs took a while).
        if (session.current !== c) {
            finish(project)
            return notify(project, NotificationType.WARNING, message("ai.prChanged"))
        }
        val settings = SmartPrReviewSettings.getInstance()
        val language = aiLanguage(repositorySafe(SmartPrReviewProjectSettings.getInstance(project).aiLanguage, c.files, ""), DynamicBundle.getLocale())
        object : Task.Backgroundable(project, message("ai.progress", engineLabel(engine)), true) {
            private var result: AiReviewResult? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    result = runAiReview(
                        AiReviewOptions(
                            session, c, profiles, engine, worktreesDir(), aiTimeoutMs(settings.aiTimeoutMinutes), settings.aiLabelComments, language, indicator,
                        ),
                    )
                } catch (_: EngineCancelled) {
                    // onCancel informs.
                }
            }

            override fun onCancel() = notify(project, NotificationType.INFORMATION, message("ai.cancelled"))

            override fun onSuccess() {
                val r = result
                when {
                    r == null -> Unit
                    r.failures.isNotEmpty() -> {
                        // The details (raw, unescaped) go to the log; the notification is HTML.
                        LOG.warn(message("ai.addedWithFailures", r.drafts, r.failures.size, describeFailures(r.failures)))
                        notify(project, NotificationType.WARNING, addedWithFailuresText(r.drafts, r.failures)) {
                            addAction(NotificationAction.createSimple(message("ai.showLog")) {
                                ActionManager.getInstance().getAction("ShowLog")?.let { ActionManager.getInstance().tryToExecute(it, null, null, "notification", true) }
                            })
                        }
                    }
                    r.reviewed.isEmpty() -> notify(project, NotificationType.INFORMATION, message("ai.noMatchAny"))
                    else -> notify(project, NotificationType.INFORMATION, message("ai.added", r.drafts, r.reviewed.size))
                }
            }

            override fun onThrowable(error: Throwable) = fail(project, error)
            override fun onFinished() = finish(project)
        }.queue()
    }

    private fun fail(project: Project, error: Throwable) {
        LOG.warn(error)
        notify(project, NotificationType.ERROR, failedText(error))
    }

    private fun finish(project: Project) = AiReviewGuard.getInstance(project).release()

    /** A stage that throws releases the guard, so a failure cannot leave the action busy. */
    private fun stage(project: Project, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            finish(project)
            fail(project, e)
        }
    }

    private fun notify(project: Project, type: NotificationType, text: String, configure: Notification.() -> Unit = {}) {
        NotificationGroupManager.getInstance().getNotificationGroup("Smart PR Review").createNotification(text, type).apply(configure).notify(project)
    }
}
