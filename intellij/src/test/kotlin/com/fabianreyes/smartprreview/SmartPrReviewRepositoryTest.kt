package com.fabianreyes.smartprreview

import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SmartPrReviewRepositoryTest : BasePlatformTestCase() {
    private fun publish(trusted: com.intellij.openapi.project.Project) =
        ApplicationManager.getApplication().messageBus.syncPublisher(TrustedProjectsListener.TOPIC).onProjectTrusted(trusted)

    fun testTrustingThisProjectRefreshesAndOtherProjectsDoNot() {
        val repository = SmartPrReviewRepository.getInstance(project)
        var received = false
        repository.onChange(testRootDisposable) { received = true }
        repository.refreshWhenTrusted(testRootDisposable)

        publish(ProjectManager.getInstance().defaultProject)
        Thread.sleep(500)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse(received)

        publish(project)
        PlatformTestUtil.waitWithEventsDispatching("refresh after trust", { received }, 10)
        assertTrue(received)
    }
}
