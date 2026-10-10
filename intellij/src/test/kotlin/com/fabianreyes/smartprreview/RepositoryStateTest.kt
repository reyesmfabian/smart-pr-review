package com.fabianreyes.smartprreview

import com.fabianreyes.smartprreview.auth.Auth
import com.fabianreyes.smartprreview.git.Git
import com.fabianreyes.smartprreview.http.HttpError
import com.fabianreyes.smartprreview.providers.MemoryViewedFiles
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.fabianreyes.smartprreview.remote.RemoteInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepositoryStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val signedIn = object : Auth {
        override fun isSignedIn() = true
        override fun signIn(force: Boolean) {}
        override fun authorization() = "x"
    }
    private val signedOut = object : Auth {
        override fun isSignedIn() = false
        override fun signIn(force: Boolean) {}
        override fun authorization(): String = throw HttpError(401, "no")
    }

    @Test
    fun `state follows trust, repository, remote and sign-in`() {
        val plain = tmp.newFolder("plain").toPath()
        assertEquals(RepositoryState.Untrusted, repositoryState(plain, trusted = false, emptyList(), MemoryViewedFiles()) { signedIn })
        assertEquals(RepositoryState.NoRepository, repositoryState(plain, trusted = true, emptyList(), MemoryViewedFiles()) { signedIn })
        assertEquals(RepositoryState.NoRepository, repositoryState(null, trusted = true, emptyList(), MemoryViewedFiles()) { signedIn })

        val repo = tmp.newFolder("repo").toPath()
        Git(tmp.root.toPath()).run("init", "-q", repo.toString())
        val git = Git(repo)
        assertEquals(RepositoryState.Unsupported, repositoryState(repo, true, emptyList(), MemoryViewedFiles()) { signedIn }) // no remote
        git.run("remote", "add", "origin", "http://tfs.contoso.com:8080/tfs/Col/Proj/_git/Repo")
        assertEquals(RepositoryState.Unsupported, repositoryState(repo, true, emptyList(), MemoryViewedFiles()) { signedIn }) // host not listed
        val server = repositoryState(repo, true, listOf("tfs.contoso.com"), MemoryViewedFiles()) { signedOut } as RepositoryState.SignedOut
        assertEquals("http://tfs.contoso.com:8080", server.remote.origin)

        git.run("remote", "set-url", "origin", "https://github.com/octo/repo.git")
        val remote = RemoteInfo(ProviderKind.GITHUB, "github.com", "https://github.com", "octo", repo = "repo")
        assertEquals(RepositoryState.SignedOut(remote, signedOut), repositoryState(repo, true, emptyList(), MemoryViewedFiles()) { signedOut })
        val ready = repositoryState(repo, true, emptyList(), MemoryViewedFiles()) { signedIn } as RepositoryState.Ready
        assertEquals(remote, ready.remote)
        assertEquals("origin", ready.remoteName)
        assertEquals(ProviderKind.GITHUB, ready.provider.kind)
        assertEquals(repo.toRealPath(), ready.git.root.toRealPath())
    }
}
