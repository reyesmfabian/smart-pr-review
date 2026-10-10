package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.RepositoryState
import com.fabianreyes.smartprreview.auth.AzureDevOpsAuth
import com.fabianreyes.smartprreview.auth.GitHubAuth
import com.fabianreyes.smartprreview.auth.MemorySecrets
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.providers.MemoryViewedFiles
import com.fabianreyes.smartprreview.providers.providerFor
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.fabianreyes.smartprreview.remote.RemoteInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.fabianreyes.smartprreview.providers.FakeProvider
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.UIUtil
import java.nio.file.Path

class WelcomeTest : BasePlatformTestCase() {
    private val github = RemoteInfo(ProviderKind.GITHUB, "github.com", "https://github.com", "octo", repo = "repo")
    private val ghAuth by lazy { GitHubAuth(project, MemorySecrets()) }
    private val azure = RemoteInfo(ProviderKind.AZURE_DEVOPS, "dev.azure.com", "https://dev.azure.com", "org", "proj", "repo")

    fun testEachStateHasItsTextAndOnlySignedOutHasAnAction() {
        assertEquals("Smart PR Review is disabled in untrusted projects. Trust this project to review its pull requests." to null, welcome(RepositoryState.Untrusted))
        assertEquals("Open a project that contains a Git repository whose remote is on GitHub or Azure DevOps. Git must be installed." to null, welcome(RepositoryState.NoRepository))
        assertEquals("This repository's remote is not on GitHub or Azure DevOps. For Azure DevOps Server, add its host in Settings | Tools | Smart PR Review." to null, welcome(RepositoryState.Unsupported))
        assertEquals("Sign in to GitHub to review the pull requests of this repository." to "Sign In", welcome(RepositoryState.SignedOut(github, GitHubAuth(project, MemorySecrets()))))
        assertEquals("Azure DevOps needs a Personal Access Token with the Code (Read & Write) scope." to "Enter Personal Access Token", welcome(RepositoryState.SignedOut(azure, AzureDevOpsAuth("dev.azure.com", MemorySecrets()))))
        assertEquals("Signed in to octo/repo." to null, welcome(RepositoryState.Ready(Git(Path.of(".")), "origin", github, ghAuth, providerFor(github, ghAuth, MemoryViewedFiles()))))
    }

    fun testPanelRendersTheState() {
        val panel = RepositoryStatePanel(project)
        panel.show(RepositoryState.NoRepository)
        assertTrue(panel.emptyText.text.contains("Open a project that contains a Git repository"))
    }

    fun testReadyShowsBothSectionsAndAnEmptyReview() {
        val panel = RepositoryStatePanel(project)
        Disposer.register(testRootDisposable, panel)
        panel.show(RepositoryState.Ready(Git(Path.of(".")), "origin", github, ghAuth, FakeProvider()))
        assertTrue(UIUtil.findComponentOfType(panel, OnePixelSplitter::class.java)!!.orientation)
        val titles = UIUtil.findComponentsOfType(panel, JBLabel::class.java).map { it.text }
        assertTrue(titles.toString(), titles.containsAll(listOf("PULL REQUESTS", "CURRENT PULL REQUEST")))
        assertTrue(UIUtil.findComponentsOfType(panel, JBPanelWithEmptyText::class.java).any { it.emptyText.text == "Select a pull request to review." })
    }

    fun testOpeningAPullRequestShowsProgressInTheCurrentSection() {
        var opening: Pair<Int, () -> Unit>? = null
        val panel = RepositoryStatePanel(project) { id, finished -> opening = id to finished }
        Disposer.register(testRootDisposable, panel)
        panel.show(RepositoryState.Ready(Git(Path.of(".")), "origin", github, ghAuth, FakeProvider()))
        val loading = UIUtil.findComponentOfType(panel, JBLoadingPanel::class.java)!!
        assertFalse(loading.isLoading)
        panel.openFromList(7)
        assertEquals(7, opening!!.first)
        assertTrue(loading.isLoading)
        opening!!.second() // opened, or failed
        assertFalse(loading.isLoading)
    }
}
