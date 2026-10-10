package com.fabianreyes.smartprreview.ui

import com.fabianreyes.smartprreview.providers.PullRequestSummary
import com.fabianreyes.smartprreview.ui.actions.searchItems
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchItemsTest {
    private val prs = listOf(PullRequestSummary(7, "Fix login", "ana", "", "u"), PullRequestSummary(8, "Add docs", "bob", "", "u"))

    @Test
    fun `filters by number, title or author`() {
        assertEquals(listOf(7, 8), searchItems(prs, "").map { it.id })
        assertEquals(listOf(8), searchItems(prs, "BOB").map { it.id })
        assertEquals(listOf(7), searchItems(prs, "#7").map { it.id })
    }

    @Test
    fun `a number that is not listed opens anyway, first`() {
        val items = searchItems(prs, " #42 ")
        assertEquals(42, items.single().id)
        assertEquals(true, items.single().typedNumber)
    }
}
