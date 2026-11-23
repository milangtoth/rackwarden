package com.homelab.app.data.repository

import com.homelab.app.data.remote.access.SignInTracker
import com.homelab.app.data.remote.api.BeszelApi
import com.homelab.app.data.remote.api.NginxProxyManagerApi
import com.homelab.app.data.remote.dto.beszel.BeszelAuthMethods
import com.homelab.app.domain.model.OAuthException
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.domain.model.TwoFactorChallenge
import com.homelab.app.domain.model.TwoFactorInvalidException
import com.homelab.app.domain.model.TwoFactorRequiredException
import com.homelab.app.util.ServiceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class SignInRepositoriesTest {
    private val server = MockWebServer()
    private val routes = mutableMapOf<String, MockResponse>()
    private val seen = mutableListOf<Pair<String, String>>()
    private val instances = mockk<ServiceInstancesRepository>(relaxed = true)
    private val tracker = SignInTracker()
    private lateinit var beszel: BeszelRepository
    private lateinit var npm: NginxProxyManagerRepository

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                synchronized(seen) { seen += path to request.body.readUtf8() }
                return routes[path]?.clone() ?: MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        server.start()
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val retrofit = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        beszel = BeszelRepository(retrofit.create(BeszelApi::class.java), instances, tracker)
        npm = NginxProxyManagerRepository(retrofit.create(NginxProxyManagerApi::class.java), instances, tracker)
    }

    @After
    fun stop() = server.shutdown()

    private val base get() = server.url("/").toString().trimEnd('/')
    private fun reply(path: String, code: Int, body: String) {
        routes[path] = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }

    private fun count(path: String) = seen.count { it.first == path }

    private inline fun <reified T : Throwable> failing(work: () -> Unit): T {
        try {
            work()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("Expected ${T::class.simpleName}")
        throw AssertionError()
    }

    private val password = "/api/collections/users/auth-with-password"
    private val oauth = "/api/collections/users/auth-with-oauth2"
    private val refresh = "/api/collections/users/auth-refresh"

    @Test
    fun `beszel asks for an emailed code and completes with it`() = runTest {
        reply(password, 401, """{"mfaId":"m1"}""")
        reply("/api/collections/users/request-otp", 200, """{"otpId":"o1"}""")
        reply("/api/collections/users/auth-with-otp", 200, """{"token":"t1","record":{"id":"1"}}""")
        val required = failing<TwoFactorRequiredException> { beszel.authenticate(base, "me@example.test", "p") }
        val sent = beszel.requestTwoFactorCode(required.challenge, false)
        assertEquals(TwoFactorChallenge.Kind.Email("me@example.test", "m1", "o1"), sent.kind)
        assertEquals("t1", beszel.completeTwoFactor(sent, "123456", false))
        assertTrue(seen.last().second.contains("\"mfaId\":\"m1\""))
        reply("/api/collections/users/auth-with-otp", 400, "{}")
        failing<TwoFactorInvalidException> { beszel.completeTwoFactor(sent, "000000", false) }
    }

    @Test
    fun `beszel still signs in with a password alone`() = runTest {
        reply(password, 200, """{"token":"t1","record":{"id":"1","email":"me@example.test"}}""")
        assertEquals("t1", beszel.authenticate(base, "me@example.test", "p"))
    }

    private val provider = BeszelAuthMethods.Provider("oidc", state = "s", authURL = "https://x?redirect_uri=", codeVerifier = "v1")

    @Test
    fun `an oauth code is exchanged with its verifier`() = runTest {
        reply(oauth, 200, """{"token":"t1","record":{"email":"me@example.test"}}""")
        val session = beszel.completeOAuth(provider, "abc", base, null, false)
        assertEquals("me@example.test", session.record?.email)
        val body = Json.parseToJsonElement(seen.single().second).toString()
        listOf("\"code\":\"abc\"", "\"codeVerifier\":\"v1\"", "\"redirectURL\":\"rackwarden://oauth\"").forEach { assertTrue(it, body.contains(it)) }
        reply(oauth, 400, """{"message":"Failed to fetch OAuth2 token.","status":400}""")
        assertEquals("Failed to fetch OAuth2 token.", failing<OAuthException> { beszel.completeOAuth(provider, "abc", base, null, false) }.detail)
        reply(oauth, 401, """{"mfaId":"m1"}""")
        failing<OAuthException> { beszel.completeOAuth(provider, "abc", base, null, false) }
        failing<TwoFactorRequiredException> { beszel.completeOAuth(provider, "abc", base, "me@example.test", false) }
    }

    private fun oauthInstance() = ServiceInstance("i", ServiceType.BESZEL, "B", base, token = "t1", username = "me@example.test")

    @Test
    fun `a revoked oauth session asks for sign-in once`() = runTest {
        coEvery { instances.getInstance("i") } returns oauthInstance()
        reply(refresh, 401, """{"status":401,"message":"no"}""")
        repeat(3) { assertNull(beszel.renewSession("i")) }
        assertTrue(tracker.isRejected("i", "t1"))
        assertEquals(1, count(refresh))
        assertEquals(0, count(password))
    }

    @Test
    fun `a proxy's refusal is not a revoked session`() = runTest {
        coEvery { instances.getInstance("i") } returns oauthInstance()
        reply(refresh, 401, "denied")
        assertNull(beszel.renewSession("i"))
        assertEquals(false, tracker.isRejected("i", "t1"))
    }

    @Test
    fun `an oauth session is renewed and stored`() = runTest {
        coEvery { instances.getInstance("i") } returns oauthInstance()
        reply(refresh, 200, """{"token":"t2"}""")
        assertEquals("t2", beszel.renewSession("i"))
        coVerify { instances.saveInstance(match { it.token == "t2" }) }
    }

    @Test
    fun `an unattended login stops when a code is needed`() = runTest {
        coEvery { instances.getInstance("i") } returns oauthInstance().copy(password = "p")
        reply(password, 401, """{"mfaId":"m1"}""")
        repeat(3) { assertNull(beszel.renewSession("i")) }
        assertTrue(tracker.isRejected("i", "t1"))
        assertEquals(1, count(password))
        assertEquals(0, count("/api/collections/users/request-otp"))
    }

    @Test
    fun `nginx proxy manager asks for a code and completes with it`() = runTest {
        reply("/api/tokens", 200, """{"requires_2fa":true,"challenge_token":"c1"}""")
        reply("/api/tokens/2fa", 200, """{"token":"t1","expires":"x"}""")
        val required = failing<TwoFactorRequiredException> { npm.authenticate(base, "me@example.test", "p") }
        assertEquals("t1", npm.completeTwoFactor(required.challenge, "123456", false))
        assertTrue(seen.last().second.contains("c1"))
        reply("/api/tokens/2fa", 401, "{}")
        failing<TwoFactorInvalidException> { npm.completeTwoFactor(required.challenge, "000000", false) }

        val instance = ServiceInstance("n", ServiceType.NGINX_PROXY_MANAGER, "N", base, token = "old", username = "u", password = "p")
        repeat(3) { assertNull(npm.renewSession(instance)) }
        assertTrue(tracker.isRejected("n", "old"))
        assertEquals(2, count("/api/tokens"))
    }

    @Test
    fun `nginx proxy manager reads tokens from body and cookie`() = runTest {
        reply("/api/tokens", 200, """{"token":"t1","expires":"x"}""")
        assertEquals("t1", npm.authenticate(base, "u", "p"))
        routes["/api/tokens"] = MockResponse().setHeader("Set-Cookie", "token=t2; Path=/; HttpOnly").setBody("{}")
        assertEquals("t2", npm.authenticate(base, "u", "p"))
    }
}
