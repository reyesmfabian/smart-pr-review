package com.fabianreyes.smartprreview.auth

import com.fabianreyes.smartprreview.http.HttpError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AzureDevOpsAuthTest {
    private val key = "smartPrReview.azureDevOps.pat:tfs.contoso.com"
    private val secrets = MemorySecrets()
    private var answer: String? = null
    private var consented = 0
    private val auth = AzureDevOpsAuth("tfs.contoso.com", secrets, beforePat = { consented++ }, prompt = { answer })

    @Test
    fun `pat is stored trimmed, removed when empty and kept on cancel`() {
        assertFalse(auth.isSignedIn())
        answer = "  abc  "
        auth.promptPat()
        assertEquals("abc", secrets.map[key])
        assertTrue(auth.isSignedIn())
        answer = null // cancel
        auth.promptPat()
        assertTrue(auth.isSignedIn())
        answer = "   " // empty = remove
        auth.promptPat()
        assertFalse(auth.isSignedIn())
    }

    @Test
    fun `authorization is basic with an empty user and runs the consent first`() {
        assertEquals(401, assertThrows(HttpError::class.java) { auth.authorization() }.status)
        assertEquals(0, consented)
        secrets.set(key, "abc")
        assertEquals("Basic " + Base64.getEncoder().encodeToString(":abc".toByteArray()), auth.authorization())
        assertEquals(1, consented)
    }

    @Test
    fun `a refusing consent keeps the token`() {
        secrets.set(key, "abc")
        val refusing = AzureDevOpsAuth("tfs.contoso.com", secrets, beforePat = { throw IllegalStateException("no") }, prompt = { null })
        assertEquals("no", assertThrows(IllegalStateException::class.java) { refusing.authorization() }.message)
    }

    @Test
    fun `the prompt receives the host`() {
        var promptedHost: String? = null
        AzureDevOpsAuth("dev.azure.com", secrets, prompt = { promptedHost = it; null }).promptPat()
        assertEquals("dev.azure.com", promptedHost)
    }
}
