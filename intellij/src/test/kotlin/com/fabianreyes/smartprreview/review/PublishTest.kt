package com.fabianreyes.smartprreview.review

import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.PullRequest
import com.fabianreyes.smartprreview.providers.PullRequestSummary
import com.fabianreyes.smartprreview.providers.Vote
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PublishTest : BasePlatformTestCase() {
    private val sha = "b".repeat(40)

    private fun open(vararg drafts: Draft, head: String = sha): OpenPullRequest {
        val store = DraftStore(MemoryStore(), "k")
        drafts.forEach(store::add)
        val pr = PullRequest(PullRequestSummary(1, "t", "ana", "", "u"), "", false, sha, head, emptyList(), null, "pr/1", null, "n")
        return OpenPullRequest(pr, sha, listOf(ChangedFile(ChangedFile.Status.M, "a.kt")), emptyList(), mutableSetOf(), store, false)
    }

    private fun draft(body: String = "x", line: Int = 1) = Draft("", "a.kt", line, line, null, null, body, sha, DraftOrigin.Manual)

    fun `test approving votes are dropped unless approval is allowed`() {
        val supported = listOf(Vote.COMMENT, Vote.APPROVE, Vote.APPROVE_WITH_SUGGESTIONS, Vote.REQUEST_CHANGES)
        assertEquals(supported, allowedVotes(supported, true))
        assertEquals(listOf(Vote.COMMENT, Vote.REQUEST_CHANGES), allowedVotes(supported, false))
    }

    fun `test revision is stable for the same content and changes with the drafts`() {
        val a = open(draft())
        val ids = a.drafts.all().map { it.id }
        assertEquals(publishRevision(a), publishRevision(a))
        a.drafts.add(draft("y", 2))
        val added = publishRevision(a)
        assertFalse(added == publishRevision(open(draft())))
        a.drafts.update(ids.first(), "edited")
        assertFalse(added == publishRevision(a))
    }

    fun `test revision changes with the head`() {
        assertFalse(publishRevision(open(draft())) == publishRevision(open(draft(), head = "c".repeat(40))))
    }

    fun `test every vote has a label`() {
        Vote.values().forEach { assertFalse(voteLabel(it).startsWith("!")) }
        assertEquals(6, Vote.values().map(::voteLabel).toSet().size)
    }
}
