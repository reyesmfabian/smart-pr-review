package com.fabianreyes.smartprreview

import com.fabianreyes.smartprreview.auth.Auth
import com.fabianreyes.smartprreview.auth.AzureDevOpsAuth
import com.fabianreyes.smartprreview.auth.GitHubAuth
import com.fabianreyes.smartprreview.auth.HttpConsent
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.providers.PropertiesViewedFilesStore
import com.fabianreyes.smartprreview.providers.PullRequestProvider
import com.fabianreyes.smartprreview.providers.ViewedFilesStore
import com.fabianreyes.smartprreview.providers.providerFor
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.fabianreyes.smartprreview.remote.RemoteInfo
import com.fabianreyes.smartprreview.remote.parseRemote
import com.fabianreyes.smartprreview.settings.SmartPrReviewSettings
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.nio.file.Path

private val LOG = logger<SmartPrReviewRepository>()

/** What the tool window shows for the project: the same states as the VS Code welcome views. */
sealed interface RepositoryState {
    /** Untrusted project (the equivalent of VS Code's Restricted Mode): the plugin stays off. */
    data object Untrusted : RepositoryState
    data object NoRepository : RepositoryState
    /** A repository whose remote is not GitHub, Azure DevOps, or a listed Azure DevOps Server. */
    data object Unsupported : RepositoryState
    data class SignedOut(val remote: RemoteInfo, val auth: Auth) : RepositoryState
    data class Ready(val git: Git, val remoteName: String, val remote: RemoteInfo, val auth: Auth, val provider: PullRequestProvider) : RepositoryState
}

/** Computes the state of a project folder. Blocking (runs git, reads credentials): never call on the EDT. */
fun repositoryState(basePath: Path?, trusted: Boolean, azureDevOpsHosts: Collection<String>, viewedFiles: ViewedFilesStore, authFor: (RemoteInfo) -> Auth): RepositoryState {
    if (!trusted) return RepositoryState.Untrusted
    val git = basePath?.let(Git::open) ?: return RepositoryState.NoRepository
    val picked = git.pickRemote()
    val remote = picked?.let { parseRemote(it.url, azureDevOpsHosts) } ?: return RepositoryState.Unsupported
    val auth = authFor(remote)
    return if (auth.isSignedIn()) RepositoryState.Ready(git, picked.name, remote, auth, providerFor(remote, auth, viewedFiles)) else RepositoryState.SignedOut(remote, auth)
}

/** Holds the project's RepositoryState and recomputes it on demand (refresh) in the background. */
@Service(Service.Level.PROJECT)
class SmartPrReviewRepository(private val project: Project) {
    @Volatile
    var state: RepositoryState = RepositoryState.NoRepository
        private set

    private val listeners = mutableListOf<(RepositoryState) -> Unit>() // EDT only

    /** The sign-in of a remote: GitHub plugin account or PAT; Azure DevOps PAT, with the HTTP warning for plain-HTTP servers. */
    fun authFor(remote: RemoteInfo): Auth = when (remote.kind) {
        ProviderKind.GITHUB -> GitHubAuth(project)
        ProviderKind.AZURE_DEVOPS -> AzureDevOpsAuth(
            remote.host,
            beforePat = if (remote.origin.startsWith("http:")) HttpConsent.forOrigin(remote.origin) else null,
        )
    }

    /** Recomputes the state on a pooled thread and tells the listeners on the EDT. */
    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val next = try {
                repositoryState(
                    project.basePath?.let(Path::of),
                    TrustedProjects.isProjectTrusted(project),
                    SmartPrReviewSettings.getInstance().azureDevOpsHosts,
                    PropertiesViewedFilesStore(project),
                    ::authFor,
                )
            } catch (e: Exception) {
                LOG.warn("Could not determine the repository state", e)
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater({
                state = next
                listeners.toList().forEach { it(next) }
            }, project.disposed)
        }
    }

    /** Recomputes when this project gets trusted. TrustedProjectsListener.TOPIC is application-level: the project bus never sees it. */
    fun refreshWhenTrusted(parent: Disposable) {
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(TrustedProjectsListener.TOPIC, object : TrustedProjectsListener {
            override fun onProjectTrusted(trusted: Project) {
                if (trusted == project) refresh()
            }
        })
    }

    private val listRefreshListeners = mutableListOf<() -> Unit>() // EDT only

    /** The pull request list asks to reload its groups (Refresh action), without recomputing the state. */
    fun onListRefresh(parent: Disposable, listener: () -> Unit) {
        listRefreshListeners += listener
        Disposer.register(parent) { listRefreshListeners -= listener }
    }

    fun refreshList() = listRefreshListeners.toList().forEach { it() }

    fun onChange(parent: Disposable, listener: (RepositoryState) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    companion object {
        fun getInstance(project: Project): SmartPrReviewRepository = project.service()
    }
}
