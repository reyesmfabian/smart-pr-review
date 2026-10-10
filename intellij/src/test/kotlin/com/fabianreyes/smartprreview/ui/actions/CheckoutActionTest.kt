package com.fabianreyes.smartprreview.ui.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CheckoutActionTest : BasePlatformTestCase() {
    fun testIsRegistered() {
        assertNotNull(ActionManager.getInstance().getAction("SmartPrReview.Checkout"))
    }
}
