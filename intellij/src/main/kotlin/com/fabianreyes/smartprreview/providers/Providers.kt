package com.fabianreyes.smartprreview.providers

import com.fabianreyes.smartprreview.auth.Auth
import com.fabianreyes.smartprreview.remote.ProviderKind
import com.fabianreyes.smartprreview.remote.RemoteInfo

/** The raw token of a GitHub Auth, whose authorization() is "Bearer <token>". */
fun githubToken(auth: Auth): String = auth.authorization().removePrefix("Bearer ")

fun providerFor(remote: RemoteInfo, auth: Auth, viewedFiles: ViewedFilesStore): PullRequestProvider = when (remote.kind) {
    ProviderKind.GITHUB -> GitHubProvider(remote.owner, remote.repo, token = { githubToken(auth) })
    ProviderKind.AZURE_DEVOPS -> AzureDevOpsProvider(remote, authorization = auth::authorization, storage = viewedFiles)
}
