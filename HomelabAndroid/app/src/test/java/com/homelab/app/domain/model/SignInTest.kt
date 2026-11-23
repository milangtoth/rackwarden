package com.homelab.app.domain.model

import com.homelab.app.data.remote.access.SignInTracker
import com.homelab.app.data.remote.dto.beszel.BeszelAuthMethods
import com.homelab.app.data.remote.dto.beszel.OAuthCallback
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SignInTest {
    @Test
    fun `the code is taken from a matching reply`() {
        assertEquals("abc", OAuthCallback.code("rackwarden://oauth?code=abc&state=s1", "s1"))
        assertEquals("a/b", OAuthCallback.code("rackwarden://oauth/?state=s1&code=a%2Fb", "s1"))
    }

    @Test
    fun `other replies are rejected`() {
        listOf(
            "rackwarden://oauth?code=abc&state=other", "rackwarden://oauth?code=abc", "rackwarden://oauth?state=s1",
            "rackwarden://oauth?code=&state=s1", "rackwarden://oauth?code=a&code=b&state=s1",
            "rackwarden://oauth?code=a&state=s1&state=s1", "rackwarden://elsewhere?code=abc&state=s1",
            "https://oauth?code=abc&state=s1", "rackwarden://oauth?error=access_denied&state=s1", "not a url %"
        ).forEach { assertThrows(it, OAuthException::class.java) { OAuthCallback.code(it, "s1") } }
        assertThrows(OAuthException::class.java) { OAuthCallback.code("rackwarden://oauth?code=abc&state=", "") }
    }

    @Test
    fun `sign-in methods are read`() {
        val json = Json { ignoreUnknownKeys = true }
        val found = json.decodeFromString<BeszelAuthMethods>(
            """{"password":{"enabled":false},"oauth2":{"enabled":true,"providers":[{"name":"oidc","displayName":"Example","state":"s1",
            "authURL":"https://auth.example.test/authorize?client_id=c&state=s1&redirect_uri=","codeVerifier":"v1","logo":"x"}]}}"""
        )
        assertFalse(found.allowsPassword)
        assertEquals(
            "https://auth.example.test/authorize?client_id=c&state=s1&redirect_uri=rackwarden%3A%2F%2Foauth",
            found.providers.single().signInUrl()
        )
        assertTrue(json.decodeFromString<BeszelAuthMethods>("{}").allowsPassword)
        assertThrows(OAuthException::class.java) {
            BeszelAuthMethods.Provider("x", state = "s", authURL = "tel:1?redirect_uri=").signInUrl()
        }
    }

    private fun jwt(iat: Long, exp: Long): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"iat":$iat,"exp":$exp}""".toByteArray())
        return "a.$payload.c"
    }

    @Test
    fun `sessions renew in the second half of their life`() {
        val token = jwt(0, 1000)
        assertFalse(TokenLifetime.shouldRenew(token, 1000.0, 400_000))
        assertTrue(TokenLifetime.shouldRenew(token, 1000.0, 600_000))
        assertFalse(TokenLifetime.shouldRenew(token, 1000.0, 1_100_000))
        assertTrue(TokenLifetime.isExpired(token, 1_100_000))
        assertFalse(TokenLifetime.isExpired("not a jwt", 1_100_000))
    }

    @Test
    fun `a rejection only counts for its own token`() {
        val tracker = SignInTracker()
        tracker.markRejected("i", "old")
        assertTrue(tracker.isRejected("i", "old"))
        assertFalse(tracker.isRejected("i", "new"))
        assertTrue(tracker.mayTry("k", 100, 0))
        assertFalse(tracker.mayTry("k", 100, 99))
        assertTrue(tracker.mayTry("k", 100, 100))
    }
}
