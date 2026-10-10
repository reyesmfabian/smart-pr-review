package com.fabianreyes.smartprreview.remote

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

enum class ProviderKind { GITHUB, AZURE_DEVOPS }

data class RemoteInfo(
    val kind: ProviderKind,
    /** API host: github.com, dev.azure.com, or the Azure DevOps Server host name. */
    val host: String,
    /** Web origin the REST calls go to, e.g. https://dev.azure.com or http://tfs.contoso.com:8080. */
    val origin: String,
    /** GitHub owner, Azure DevOps organization, or Azure DevOps Server collection path. */
    val owner: String,
    /** Azure DevOps project. */
    val project: String? = null,
    val repo: String,
)

private const val CLOUD_HOST = "dev.azure.com"

fun parseRemote(raw: String, azureDevOpsHosts: Collection<String>): RemoteInfo? {
    val url = toUri(raw.trim()) ?: return null
    val host = url.host.lowercase()
    val parts = url.rawPath.split("/").filter { it.isNotEmpty() }.map(::decode).toMutableList()
    if (parts.isNotEmpty()) parts[parts.lastIndex] = parts.last().removeSuffix(".git")

    if (host == "github.com") {
        return if (parts.size >= 2) RemoteInfo(ProviderKind.GITHUB, host, "https://github.com", parts[0], repo = parts[1]) else null
    }
    fun cloud(owner: String, project: String, repo: String) = RemoteInfo(ProviderKind.AZURE_DEVOPS, CLOUD_HOST, "https://$CLOUD_HOST", owner, project, repo)
    if (host == "ssh.dev.azure.com" || host == "vs-ssh.visualstudio.com") {
        // v3/{organization}/{project}/{repo}
        return if (parts.size == 4 && parts[0] == "v3") cloud(parts[1], parts[2], parts[3]) else null
    }

    // HTTPS Azure DevOps remotes: .../{project}/_git/{repo}
    val git = parts.indexOf("_git")
    if (git < 1 || git != parts.size - 2) return null
    val project = parts[git - 1]
    val repo = parts[git + 1]
    if (host == CLOUD_HOST) return if (git == 2) cloud(parts[0], project, repo) else null
    if (host.endsWith(".visualstudio.com")) return cloud(host.substringBefore("."), project, repo)
    if (git >= 2 && azureDevOpsHosts.any { it.lowercase() == host }) {
        // Keep the scheme and port of HTTP(S) remotes (on-premises servers often use http://host:8080); SSH remotes use HTTPS.
        val scheme = url.scheme?.lowercase()
        val origin = if (scheme == "http" || scheme == "https") "$scheme://$host" + (if (url.port != -1) ":${url.port}" else "") else "https://$host"
        return RemoteInfo(ProviderKind.AZURE_DEVOPS, host, origin, parts.subList(0, git - 1).joinToString("/"), project, repo)
    }
    return null
}

private val SCP = Regex("""^[^@/]+@([^:/]+):(.+)$""")

/** scp-like syntax (git@host:path) becomes ssh://host/path; anything java.net.URI rejects, or without a host, is no remote we know. */
private fun toUri(raw: String): URI? {
    val scp = SCP.find(raw)
    val text = if (scp != null) "ssh://${scp.groupValues[1]}/${scp.groupValues[2]}" else raw
    return try {
        URI(text).takeIf { it.host != null }
    } catch (e: URISyntaxException) {
        null
    }
}

/** Percent-decoding like decodeURIComponent: a literal '+' stays a '+'. */
private fun decode(segment: String): String = try {
    URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8)
} catch (e: IllegalArgumentException) {
    segment
}
