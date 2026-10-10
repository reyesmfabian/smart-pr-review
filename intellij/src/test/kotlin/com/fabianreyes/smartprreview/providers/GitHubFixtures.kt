package com.fabianreyes.smartprreview.providers

internal const val UPDATED = "2026-09-01T00:00:00Z"
internal const val PR_URL = "https://github.com/octo/app/pull/7"
internal val BASE_SHA = "b".repeat(40)
internal val HEAD_SHA = "c".repeat(40)

internal val pr = PullRequest(
    summary = PullRequestSummary(7, "Fix", "ana", UPDATED, PR_URL),
    body = "", isDraft = false, baseSha = BASE_SHA, headSha = HEAD_SHA, fetchRefspecs = emptyList(),
    checkoutBranch = "fix", headBranch = "fix", nodeId = "PR_kw7",
)

internal fun draft(id: String, startLine: Int, endLine: Int) = Draft(
    id = id, path = "src/app.ts", startLine = startLine, endLine = endLine, body = "note $id",
    headSha = HEAD_SHA, origin = DraftOrigin.Manual,
)

/** GitHub's pull request, from the branch "fix" of `headRepo`. */
internal fun ghPull(headRepo: String?): Map<String, Any?> = mapOf(
    "number" to 7, "title" to "Fix", "user" to mapOf("login" to "ana"), "updated_at" to UPDATED, "html_url" to PR_URL,
    "body" to null, "draft" to false, "node_id" to "PR_kw7",
    "base" to mapOf("ref" to "main", "sha" to BASE_SHA, "repo" to mapOf("full_name" to "octo/app")),
    "head" to mapOf("ref" to "fix", "sha" to HEAD_SHA, "repo" to headRepo?.let { mapOf("full_name" to it) }),
)

internal fun thread(id: String, vararg overrides: Pair<String, Any?>): Map<String, Any?> = mapOf(
    "id" to id, "isResolved" to false, "isOutdated" to false, "path" to "src/app.ts", "line" to 5, "startLine" to null,
    "originalLine" to 5, "originalStartLine" to null, "diffSide" to "RIGHT",
    "comments" to mapOf(
        "nodes" to listOf(
            mapOf("id" to "$id-c", "body" to "Why?", "createdAt" to UPDATED, "author" to mapOf("login" to "ana"), "originalCommit" to mapOf("oid" to "d".repeat(40))),
        ),
    ),
) + overrides
