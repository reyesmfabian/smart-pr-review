package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.git.ChangedFile
import com.fabianreyes.smartprreview.providers.Draft
import com.fabianreyes.smartprreview.providers.DraftOrigin
import com.fabianreyes.smartprreview.providers.PullRequest
import com.fabianreyes.smartprreview.providers.PullRequestSummary
import com.fabianreyes.smartprreview.providers.Vote
import com.fabianreyes.smartprreview.review.DraftStore
import com.fabianreyes.smartprreview.review.MemoryStore
import com.fabianreyes.smartprreview.review.OpenPullRequest
import com.fabianreyes.smartprreview.review.publishRevision
import org.junit.Assert.assertEquals
import org.junit.Test

class PublishDialogModelTest {
    private val sha = "b".repeat(40)
    private val votes = listOf(Vote.COMMENT, Vote.APPROVE, Vote.REQUEST_CHANGES)

    private fun open(summary: String = "", vararg lines: Int): OpenPullRequest {
        val store = DraftStore(MemoryStore(), "k")
        lines.forEach { store.add(Draft("", "a.kt", it, it, null, null, "x", sha, DraftOrigin.Manual)) }
        store.setSummary(summary)
        val pr = PullRequest(PullRequestSummary(1, "t", "ana", "", "u"), "", false, sha, sha, emptyList(), null, "pr/1", null, "n")
        return OpenPullRequest(pr, sha, listOf(ChangedFile(ChangedFile.Status.M, "a.kt")), emptyList(), mutableSetOf(), store, false)
    }

    @Test
    fun `model carries drafts by position, stored summary and the revision`() {
        val c = open("sum", 9, 2)
        val m = publishModel(c, votes, true, null)
        assertEquals(listOf(2, 9), m.drafts.map { it.startLine })
        assertEquals("sum", m.summary)
        assertEquals(publishRevision(c), m.revision)
    }

    @Test
    fun `votes drop approvals when approval is not allowed`() {
        assertEquals(listOf(Vote.COMMENT, Vote.REQUEST_CHANGES), publishModel(open(), votes, false, null).votes)
    }

    @Test
    fun `last vote is kept when still allowed, else the first one`() {
        assertEquals(Vote.REQUEST_CHANGES, publishModel(open(), votes, true, Vote.REQUEST_CHANGES).selected)
        assertEquals(Vote.COMMENT, publishModel(open(), votes, false, Vote.APPROVE).selected)
        assertEquals(Vote.COMMENT, publishModel(open(), votes, true, null).selected)
    }
}
