package com.fabianreyes.smartprreview.ai

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/** One AI review at a time per project: a second click cannot start another one unnoticed. */
@Service(Service.Level.PROJECT)
class AiReviewGuard {
    private val running = AtomicBoolean()

    fun tryAcquire(): Boolean = running.compareAndSet(false, true)
    fun release() = running.set(false)

    companion object {
        fun getInstance(project: Project): AiReviewGuard = project.service()
    }
}
