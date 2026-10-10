package com.fabianreyes.smartprreview.http

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.time.Instant

class DescribeErrorTest : BasePlatformTestCase() {
    fun testTranslatesEachStatus() {
        assertEquals("Your session expired or you are not signed in. Sign in and try again.", describeError(HttpError(401, "ignored")))
        assertEquals("Permission denied: nope. Check that your account or token can access this repository.", describeError(HttpError(403, "nope")))
        assertEquals("Not found: gone. The pull request or repository may not exist, or you may not have access.", describeError(HttpError(404, "gone")))
        assertEquals("The server rejected the request: bad", describeError(HttpError(422, "bad")))
        assertEquals("Request failed (500): boom", describeError(HttpError(500, "boom")))
        assertEquals("network down", describeError(IllegalStateException("network down")))
    }

    fun testRateLimitWinsOverForbidden() {
        val headers = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to Instant.now().plusSeconds(600).epochSecond.toString())
        val text = describeError(HttpError(403, "rate limited", headers))
        assertTrue(text, text.startsWith("GitHub rate limit reached. Try again after "))
        // Without the remaining=0 header it is an ordinary 403.
        assertTrue(describeError(HttpError(403, "x", mapOf("x-ratelimit-reset" to "1"))).startsWith("Permission denied"))
    }
}
