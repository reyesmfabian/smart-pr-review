package com.fabianreyes.smartprreview.remote

import com.fabianreyes.smartprreview.remote.ProviderKind.AZURE_DEVOPS
import com.fabianreyes.smartprreview.remote.ProviderKind.GITHUB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseRemoteTest {
    private val hosts = listOf("TFS.contoso.com")
    private val cloud = RemoteInfo(AZURE_DEVOPS, "dev.azure.com", "https://dev.azure.com", "org", "proj", "repo")

    @Test
    fun `github over https and scp`() {
        val expected = RemoteInfo(GITHUB, "github.com", "https://github.com", "octo", repo = "repo")
        assertEquals(expected, parseRemote("https://github.com/octo/repo.git", hosts))
        assertEquals(expected, parseRemote("git@github.com:octo/repo.git", hosts))
        assertEquals(expected, parseRemote("  ssh://git@GitHub.com/octo/repo  ", hosts))
        assertNull(parseRemote("https://github.com/octo", hosts))
    }

    @Test
    fun `azure devops services over https`() {
        assertEquals(cloud, parseRemote("https://org@dev.azure.com/org/proj/_git/repo", hosts))
        assertNull(parseRemote("https://dev.azure.com/org/extra/proj/_git/repo", hosts))
        assertNull(parseRemote("https://dev.azure.com/org/proj/repo", hosts))
    }

    @Test
    fun `azure devops ssh v3 and visualstudio hosts`() { // Review Focus 2
        assertEquals(cloud, parseRemote("git@ssh.dev.azure.com:v3/org/proj/repo", hosts))
        assertEquals(cloud, parseRemote("org@vs-ssh.visualstudio.com:v3/org/proj/repo", hosts))
        assertEquals(cloud, parseRemote("https://org.visualstudio.com/proj/_git/repo", hosts))
        assertEquals(cloud, parseRemote("https://org.visualstudio.com/DefaultCollection/proj/_git/repo", hosts))
        assertNull(parseRemote("git@ssh.dev.azure.com:v2/org/proj/repo", hosts))
    }

    @Test
    fun `azure devops server keeps scheme and port and needs the host listed`() { // Review Focus 1
        val http = parseRemote("http://tfs.contoso.com:8080/tfs/DefaultCollection/Proj/_git/Repo", hosts)
        assertEquals(RemoteInfo(AZURE_DEVOPS, "tfs.contoso.com", "http://tfs.contoso.com:8080", "tfs/DefaultCollection", "Proj", "Repo"), http)
        assertEquals("https://tfs.contoso.com", parseRemote("ssh://git@tfs.contoso.com:22/tfs/DefaultCollection/Proj/_git/Repo", hosts)?.origin)
        assertEquals("https://tfs.contoso.com", parseRemote("https://user@tfs.contoso.com/tfs/DefaultCollection/Proj/_git/Repo", hosts)?.origin)
        assertEquals("http://tfs.contoso.com:8080", parseRemote("http://user@tfs.contoso.com:8080/tfs/DefaultCollection/Proj/_git/Repo", hosts)?.origin)
        // Encoded userinfo must never leak into the origin (it once pointed at evil.com).
        assertEquals("https://tfs.contoso.com", parseRemote("https://a%40evil.com%2F@tfs.contoso.com/Col/Proj/_git/Repo", hosts)?.origin)
        assertEquals("https://tfs.contoso.com", parseRemote("https://a%40evil.com%3F@tfs.contoso.com/Col/Proj/_git/Repo", hosts)?.origin)
        assertNull(parseRemote("http://tfs.contoso.com:8080/tfs/DefaultCollection/Proj/_git/Repo", emptyList()))
        assertNull(parseRemote("http://tfs.contoso.com/Proj/_git/Repo", hosts)) // no collection segment
    }

    @Test
    fun `percent-encoded segments and garbage`() {
        assertEquals("my proj", parseRemote("https://dev.azure.com/org/my%20proj/_git/repo", hosts)?.project)
        assertEquals("a+b", parseRemote("https://dev.azure.com/org/a+b/_git/repo", hosts)?.project)
        assertNull(parseRemote("not a url", hosts))
        assertNull(parseRemote("", hosts))
        assertNull(parseRemote("C:\\repos\\thing", hosts))
    }
}
