package com.fabianreyes.smartprreview.ui.actions

import com.fabianreyes.smartprreview.ui.gearActions
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ToolWindowActionsTest : BasePlatformTestCase() {
    private val ids = listOf("Refresh", "Search", "OpenInBrowser", "Checkout", "CreateWorktree", "ManageWorktrees", "OpenReviewWindow", "PrepareReview", "ReviewWithAi", "CreateProfile", "ExitReview", "DiscardDrafts").map { "SmartPrReview.$it" }

    fun testEveryActionIsRegistered() {
        ids.forEach { assertNotNull(it, ActionManager.getInstance().getAction(it)) }
    }

    fun testDiffEditorActionsAreInTheDiffEditorPopup() {
        val manager = ActionManager.getInstance()
        val popup = manager.getAction("Diff.EditorPopupMenu") as ActionGroup
        val inPopup = popup.getChildren(null).map { manager.getId(it) }
        listOf("SmartPrReview.AddDraft", "SmartPrReview.AddSuggestion").forEach {
            assertNotNull(it, manager.getAction(it))
            assertTrue(it, it in inPopup)
        }
    }

    fun testToolWindowGroupHoldsTheActionsInOrder() {
        val manager = ActionManager.getInstance()
        val group = manager.getAction("SmartPrReview.ToolWindow") as ActionGroup
        assertEquals(ids, group.getChildren(null).map { manager.getId(it) })
    }

    fun testTheMoreMenuHoldsOnlyTheRareActions() {
        val manager = ActionManager.getInstance()
        // Refresh and Search are on the title bar, the rest on the Current Pull Request header.
        assertEquals(listOf("SmartPrReview.DiscardDrafts", "SmartPrReview.CreateProfile", "SmartPrReview.ManageWorktrees"), gearActions(manager).getChildren(null).map { manager.getId(it) })
    }
}
