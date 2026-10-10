package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.SmartPrReviewBundle.message
import com.fabianreyes.smartprreview.http.toJson
import com.fabianreyes.smartprreview.providers.Vote
import java.security.MessageDigest

/** Votes that approve the pull request, hidden when the allowApproval setting is off. */
val APPROVING = setOf(Vote.APPROVE, Vote.APPROVE_WITH_SUGGESTIONS)

fun allowedVotes(supported: List<Vote>, allowApproval: Boolean): List<Vote> =
    if (allowApproval) supported else supported.filter { it !in APPROVING }

/** Binds the preview to the exact comments, not just their count (AI may add drafts while it is open). */
fun publishRevision(c: OpenPullRequest): String {
    val json = toJson(listOf(c.pr.id, c.pr.headSha, c.drafts.all().map { listOf(it.id, it.path, it.startLine, it.endLine, it.body) }))
    return MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun voteLabel(vote: Vote): String = when (vote) {
    Vote.COMMENT -> message("vote.comment")
    Vote.APPROVE -> message("vote.approve")
    Vote.APPROVE_WITH_SUGGESTIONS -> message("vote.approveWithSuggestions")
    Vote.REQUEST_CHANGES -> message("vote.requestChanges")
    Vote.WAIT_FOR_AUTHOR -> message("vote.waitForAuthor")
    Vote.REJECT -> message("vote.reject")
}
