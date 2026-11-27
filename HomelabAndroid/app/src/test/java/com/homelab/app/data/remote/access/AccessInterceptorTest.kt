package com.homelab.app.data.remote.access

import com.homelab.app.domain.model.AccessCookie
import com.homelab.app.domain.model.AccessOrigin
import com.homelab.app.domain.model.AccessProfile
import com.homelab.app.domain.model.OriginAccess
import com.homelab.app.util.GlobalEventBus
import com.homelab.app.util.ProxyExpired
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AccessInterceptorTest {
    private val service = MockWebServer()
    private val elsewhere = MockWebServer()
    private val bus = mockk<GlobalEventBus>(relaxed = true)
    private val store = AccessProfileStore(mockk<AccessProfileStorage> { every { load() } returns emptyMap(); every { save(any()) } returns Unit })
    private lateinit var client: OkHttpClient

    @Before
    fun start() {
        service.start()
        elsewhere.start()
        client = OkHttpClient.Builder()
            .addInterceptor(InstanceTagInterceptor())
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().removeHeader("X-Homelab-Instance-Id").build()) }
            .addInterceptor(AccessInterceptor(store, bus, allowSelfSigned = false))
            .addNetworkInterceptor(ProxyGuardInterceptor())
            .build()
        val origin = AccessOrigin.from(service.url("/").toString())!!
        store.set(
            AccessProfile(
                isEnabled = true, allowsInsecureTransport = true,
                origins = listOf(OriginAccess(origin, cookies = listOf(AccessCookie("gate", "open"))))
            ),
            "i1"
        )
    }

    @After
    fun stop() {
        service.shutdown()
        elsewhere.shutdown()
    }

    private fun get(url: String, instance: String? = "i1"): Response {
        val request = Request.Builder().url(url)
        instance?.let { request.header("X-Homelab-Instance-Id", it) }
        return client.newCall(request.build()).execute()
    }

    /** A different host on the same machine. */
    private fun other(path: String) = elsewhere.url(path).newBuilder().host("127.0.0.1").build().toString()

    @Test
    fun `sends the session to its own service`() {
        service.enqueue(MockResponse().setBody("{}"))
        get(service.url("/api").toString()).close()
        val seen = service.takeRequest()
        assertEquals("gate=open", seen.getHeader("Cookie"))
        assertNull(seen.getHeader("X-Homelab-Instance-Id"))
    }

    @Test
    fun `other addresses and instances get nothing`() {
        elsewhere.enqueue(MockResponse().setBody("{}"))
        get(elsewhere.url("/").toString()).close()
        assertNull(elsewhere.takeRequest().getHeader("Cookie"))
        service.enqueue(MockResponse().setBody("{}"))
        get(service.url("/").toString(), "i2").close()
        assertNull(service.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `logins without an instance are matched by address`() {
        service.enqueue(MockResponse().setBody("{}"))
        get(service.url("/login").toString(), null).close()
        assertEquals("gate=open", service.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `redirects elsewhere are stopped before anything is sent`() {
        service.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other("/login")))
        assertThrows(ForwardAuthRequiredException::class.java) { get(service.url("/api").toString()) }
        assertEquals(0, elsewhere.requestCount)
        verify { bus.emitProxyExpired(match<ProxyExpired> { it.instanceId == "i1" }) }
    }

    @Test
    fun `the proxy's own pages count as a sign-in`() {
        service.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/outpost.goauthentik.io/start"))
        assertThrows(ForwardAuthRequiredException::class.java) { get(service.url("/api").toString()) }
        assertEquals(1, service.requestCount)
    }

    @Test
    fun `redirects within the service are followed`() {
        service.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        service.enqueue(MockResponse().setBody("ok"))
        assertEquals("ok", get(service.url("/api").toString()).use { it.body!!.string() })
    }

    @Test
    fun `an unexpected login page is recognized without a profile`() {
        service.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other("/login")))
        elsewhere.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>"))
        assertThrows(ForwardAuthRequiredException::class.java) { get(service.url("/api").toString(), "i2") }
        assertNull(elsewhere.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `a WebSocket handshake carries the session and reports a sign-in`() {
        val sockets = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        service.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other("/login")))
        val failure = CompletableFuture<Throwable>()
        sockets.newWebSocket(
            Request.Builder().url(service.url("/ws").toString().replace("http", "ws")).build(),
            object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure.complete(t)
                }
            }
        )
        assertTrue(failure.get(5, TimeUnit.SECONDS) is ForwardAuthRequiredException)
        assertEquals("gate=open", service.takeRequest().getHeader("Cookie"))
        assertEquals(0, elsewhere.requestCount)
    }
}
