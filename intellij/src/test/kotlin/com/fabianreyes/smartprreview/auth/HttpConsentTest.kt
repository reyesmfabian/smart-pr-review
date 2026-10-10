package com.fabianreyes.smartprreview.auth

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Review Focus 4. BasePlatformTestCase because the refusal text comes from the bundle. */
class HttpConsentTest : BasePlatformTestCase() {
    private var remembered = false
    private val answers = ArrayDeque<Boolean>()
    private var asked = 0

    private fun consent(ask: (String) -> Boolean = { asked++; answers.removeFirst() }) =
        HttpConsent("http://tfs:8080", { remembered }, { remembered = true }, ask)

    fun testWithoutConsentTheTokenIsNotSentAndANoIsAskedAgain() {
        answers.addAll(listOf(false, true))
        val consent = consent()
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) { consent() }
        assertEquals("The personal access token was not sent: http://tfs:8080 uses HTTP, which does not encrypt it. Use an HTTPS remote, or accept sending it over HTTP.", error.message)
        assertFalse(remembered)
        consent() // asks again, answer is yes
        assertTrue(remembered)
        consent() // remembered: no question
        assertEquals(2, asked)
    }

    fun testParallelRequestsShareOneQuestion() {
        val release = CountDownLatch(1)
        val questions = AtomicInteger()
        val consent = consent { questions.incrementAndGet(); release.await(5, TimeUnit.SECONDS); true }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val calls = (1..4).map { pool.submit { consent() } }
            // ponytail: a short wait lets the 4 callers reach the shared question before it is answered.
            Thread.sleep(300)
            release.countDown()
            calls.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, questions.get())
        assertTrue(remembered)
    }
}
