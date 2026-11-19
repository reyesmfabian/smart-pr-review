package com.fabianreyes.smartprreview.ai

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.auth.PasswordSafeSecrets
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

/** How long an engine may take to list its models before the picker goes on without them. */
private const val LIST_MODELS_TIMEOUT_MS = 20_000L

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
                            when {
                                choice.settings.url != null -> chooseModel(project, session, c, selected, choice)
                                engine == null -> {
                                    finish(project)
                                    reportMissing(project, choice)
                                }
                                choice.settings.modelArg == null -> review(project, session, c, selected, engine)
                                else -> chooseModel(project, session, c, selected, choice)
                            }
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

    /**
     * Asks for the model of an engine that declares its model flag, or of a URL engine, after loading the models the
     * CLI or the server lists (when it can).
     */
    private fun chooseModel(project: Project, session: ReviewSession, c: OpenPullRequest, profiles: List<Profile>, choice: EngineChoice) {
        val settings = choice.settings
        val url = settings.url
        var apiKey: String? = null
        fun engine(model: String?): ReviewEngine =
            if (url != null) {
                UrlEngine(choice.id, model ?: error("a URL engine needs a model"), url, settings.contextWindow, settings.maxOutputTokens, apiKey, settings.reasoningEffort)
            }
            else choice.toEngine(model) ?: error("engine without executable")
        fun show(listed: List<CliModel>) = stage(project) {
            pickModel(
                project, choice, listed,
                onPicked = { model -> stage(project) { review(project, session, c, profiles, engine(model)) } },
                onDismissed = { finish(project) },
            )
        }
        if (url == null && settings.listModelsArgs.isEmpty()) return show(emptyList())
        object : Task.Backgroundable(project, message("ai.loadingModels", choice.id), true) {
            private var listed = emptyList<CliModel>()

            override fun run(indicator: ProgressIndicator) {
                listed = try {
                    if (url != null) {
                        apiKey = PasswordSafeSecrets.get(apiKeySecret(url))
                        listUrlModels(url, apiKey, LIST_MODELS_TIMEOUT_MS)
                    } else {
                        listCliModels(checkNotNull(choice.executable).toString(), settings.listModelsArgs, indicator, LIST_MODELS_TIMEOUT_MS)
                    }
                } catch (_: EngineCancelled) {
                    emptyList() // onCancel releases the guard.
                } catch (e: Exception) {
                    // Not signed in, offline, an older CLI, a server down: the picker still offers the engine's models and "Other…".
                    LOG.warn("Could not list the models of ${choice.id}: ${e.message}")
                    emptyList()
                }
            }

            override fun onSuccess() = show(listed)
            override fun onCancel() = finish(project)
            override fun onThrowable(error: Throwable) {
                finish(project)
                fail(project, error)
            }
        }.queue()
    }

    private fun review(project: Project, session: ReviewSession, c: OpenPullRequest, profiles: List<Profile>, engine: ReviewEngine) {
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
                    // Closing the project or the IDE stops the engines too.
                    result = cancelWhenDisposed(session, indicator) {
                        runAiReview(
                            AiReviewOptions(
                                session, c, profiles, engine, worktreesDir(), aiTimeoutMs(settings.aiTimeoutMinutes), settings.aiLabelComments, language, indicator,
                            ),
                        )
                    }
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
