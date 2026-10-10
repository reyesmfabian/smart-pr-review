package com.fabianreyes.smartprreview.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Per-project settings (stored in .idea, so a repository can share them). */
@Service(Service.Level.PROJECT)
@State(name = "SmartPrReviewProjectSettings", storages = [Storage("smartPrReview.xml")])
class SmartPrReviewProjectSettings : PersistentStateComponent<SmartPrReviewProjectSettings.State> {
    class State {
        /** Folder of the repository with the review profiles. */
        var profilesPath: String = ".smart-review/profiles"
        /** Language of the AI review; empty = the pull request's, or the IDE's. */
        var aiLanguage: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    val profilesPath: String get() = state.profilesPath
    val aiLanguage: String get() = state.aiLanguage

    companion object {
        fun getInstance(project: Project): SmartPrReviewProjectSettings = project.service()
    }
}
