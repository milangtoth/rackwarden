package com.homelab.app.data.remote.access

import com.homelab.app.domain.model.AccessCookie
import com.homelab.app.domain.model.AccessHeader
import com.homelab.app.domain.model.AccessMethod
import com.homelab.app.domain.model.AccessOrigin
import com.homelab.app.domain.model.AccessProfile
import com.homelab.app.domain.model.OriginAccess
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.ui.forwardauth.ForwardAuthForm
import com.homelab.app.ui.forwardauth.ForwardAuthWebData
import com.homelab.app.util.ServiceType
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessProfileTest {
    private val origin = AccessOrigin.from("https://app.example.test")!!

    private fun profile(
        headers: List<AccessHeader> = listOf(AccessHeader("X-Proxy", "secret")),
        cookies: List<AccessCookie> = emptyList()
    ) = AccessProfile(isEnabled = true, origins = listOf(OriginAccess(origin, headers, cookies)))

    private fun request(url: String) = Request.Builder().url(url).build()

    private class Storage : AccessProfileStorage {
        var saved: Map<String, AccessProfile> = emptyMap()
        override fun load() = saved
        override fun save(profiles: Map<String, AccessProfile>) {
            saved = profiles
        }
    }

    @Test
    fun `origins are normalized`() {
        assertEquals(origin, AccessOrigin.from("WSS://APP.example.test:443/x"))
        assertNotEquals(origin, AccessOrigin.from("https://app.example.test:8443"))
        assertNotEquals(origin, AccessOrigin.from("http://app.example.test"))
        assertNull(AccessOrigin.from("file:///tmp"))
        assertNull(AccessOrigin.from(null))
        assertNull(AccessOrigin.from("not a url"))
    }

    @Test
    fun `credentials are only prepared for their origin`() {
        val sent = AccessInterceptor.prepare(request("https://app.example.test/api"), profile(), origin, false)
        assertEquals("secret", sent.header("X-Proxy"))
        val other = AccessOrigin.from("https://app.example.test.evil.test")!!
        assertNull(AccessInterceptor.prepare(request("https://app.example.test.evil.test"), profile(), other, false).header("X-Proxy"))
    }

    @Test
    fun `unverified connections need consent`() {
        assertThrows(ForwardAuthInsecureException::class.java) {
            AccessInterceptor.prepare(request("https://app.example.test"), profile(), origin, allowSelfSigned = true)
        }
        val allowed = profile().copy(allowsInsecureTransport = true)
        assertNotNull(AccessInterceptor.prepare(request("https://app.example.test"), allowed, origin, true).header("X-Proxy"))
        val plainOrigin = AccessOrigin.from("http://app.example.test")!!
        val plain = AccessProfile(isEnabled = true, origins = listOf(OriginAccess(plainOrigin)))
        assertThrows(ForwardAuthInsecureException::class.java) {
            AccessInterceptor.prepare(request("http://app.example.test"), plain, plainOrigin, false)
        }
    }

    @Test
    fun `the service's own headers and cookies win`() {
        val p = profile(
            listOf(AccessHeader("Authorization", "Basic proxy")),
            listOf(AccessCookie("SID", "proxy"), AccessCookie("gate", "a=b"))
        )
        val sent = AccessInterceptor.prepare(
            request("https://app.example.test/api").newBuilder()
                .header("Authorization", "Bearer service").header("Cookie", "SID=service").build(),
            p, origin, false
        )
        assertEquals("Bearer service", sent.header("Authorization"))
        assertEquals("SID=service; gate=a=b", sent.header("Cookie"))
    }

    @Test
    fun `cookies follow path and expiry`() {
        assertTrue(AccessCookie("a", "b", "/app").appliesTo("/app/x", 0))
        assertFalse(AccessCookie("a", "b", "/app").appliesTo("/apple", 0))
        assertFalse(AccessCookie("a", "b", "/", expiresAt = 5).appliesTo("/", 5))
    }

    @Test
    fun `headers are validated`() {
        assertTrue(AccessHeader("CF-Access-Client-Id", "x").isValid)
        assertFalse(AccessHeader("Cookie", "x").isValid)
        assertFalse(AccessHeader("Bad Name", "x").isValid)
        assertFalse(AccessHeader("X", "a\r\nHost: evil").isValid)
        val authorization = AccessHeader("Authorization", "Basic x")
        assertFalse(ForwardAuth.canSend(authorization, ServiceType.BESZEL, usesNativeLogin = true))
        assertTrue(ForwardAuth.canSend(authorization, ServiceType.RADARR, usesNativeLogin = true))
        assertTrue(ForwardAuth.canSend(authorization, ServiceType.UPTIME_KUMA, usesNativeLogin = false))
        assertFalse(ForwardAuth.canSend(AccessHeader("X-Homelab-Instance-Id", "x"), ServiceType.RADARR, true))
    }

    @Test
    fun `drafts stay in memory until saved`() {
        val storage = Storage()
        val store = AccessProfileStore(storage)
        store.setDraft("d", profile(), listOf("https://app.example.test"))
        assertNotNull(store.match(null, origin))
        assertNull(store.match("other", origin))
        assertTrue(storage.saved.isEmpty())
        store.discardDraft("d")
        assertNull(store.match(null, origin))

        store.setDraft("d", profile(), listOf("https://app.example.test"))
        store.commitDraft("d", ServiceInstance("d", ServiceType.BESZEL, "x", "https://moved.example.test"))
        assertEquals(emptyList<OriginAccess>(), storage.saved["d"]?.origins)
    }

    @Test
    fun `turning forward auth off applies to the test login and deletes on save`() {
        val storage = Storage()
        val store = AccessProfileStore(storage)
        store.set(profile(), "i")
        assertNotNull(store.match(null, origin))
        store.setDraft("i", null, listOf("https://app.example.test"))
        assertNull(store.match(null, origin))
        store.commitDraft("i", ServiceInstance("i", ServiceType.BESZEL, "x", "https://app.example.test"))
        assertTrue(storage.saved.isEmpty())
    }

    @Test
    fun `signing out keeps headers and settings`() {
        val store = AccessProfileStore(Storage())
        store.set(profile(cookies = listOf(AccessCookie("gate", "x"))), "i")
        store.signOut("i")
        val left = store.profile("i")!!.origins.single()
        assertTrue(left.cookies.isEmpty())
        assertEquals(1, left.headers.size)
    }

    @Test
    fun `profiles read from and written for iOS backups`() {
        val ios = """{"isEnabled":true,"method":"headers","usesNativeLogin":false,"allowsInsecureTransport":false,
            "origins":[{"origin":{"scheme":"https","host":"app.example.test","port":443},"headers":[{"name":"X-Proxy","value":"h"}],"cookies":[]}]}"""
        val read = Json.decodeFromString<AccessProfile>(ios)
        assertEquals(AccessMethod.HEADERS, read.method)
        assertFalse(read.usesNativeLogin)
        // iOS needs every field, including those with default values.
        val written = Json.encodeToString(AccessProfile.serializer(), AccessProfile(origins = listOf(OriginAccess(origin))))
        listOf("isEnabled", "\"browser\"", "usesNativeLogin", "allowsInsecureTransport", "headers", "cookies").forEach {
            assertTrue(it, written.contains(it))
        }
    }

    @Test
    fun `outcomes compare answers with and without credentials`() {
        assertEquals(ProxyOutcome.NOT_NEEDED, ForwardAuth.outcome(200, null))
        assertEquals(ProxyOutcome.PASSED, ForwardAuth.outcome(null, 200))
        assertEquals(ProxyOutcome.PASSED, ForwardAuth.outcome(null, 401))
        assertEquals(ProxyOutcome.BLOCKED, ForwardAuth.outcome(null, null))
        assertEquals(ProxyOutcome.BLOCKED, ForwardAuth.outcome(401, 401))
        assertEquals(ProxyOutcome.PASSED, ForwardAuth.outcome(403, 404))
        assertEquals(ProxyOutcome.BLOCKED, ForwardAuth.outcome(null, 502))
    }

    @Test
    fun `proxy pages are not service pages`() {
        assertTrue(ForwardAuth.isServicePage("https://app.example.test/login?x=a b|c", origin))
        assertFalse(ForwardAuth.isServicePage("https://app.example.test/outpost.goauthentik.io/start?rd=x", origin))
        assertFalse(ForwardAuth.isServicePage("https://auth.example.test/", origin))
        assertFalse(ForwardAuth.isServicePage(null, origin))
    }

    @Test
    fun `browser cookies are read`() {
        val cookies = ForwardAuthWebData.parse("a=1; b=x=y; =bad; broken")
        assertEquals(listOf("a" to "1", "b" to "x=y"), cookies.map { it.name to it.value })
        assertTrue(ForwardAuthWebData.parse(null).isEmpty())
    }

    @Test
    fun `edited headers need a new check`() {
        val form = ForwardAuthForm()
        form.method = AccessMethod.HEADERS
        form.rows[0] = ForwardAuthForm.Row("X-Proxy", "one")
        form.connect(OriginAccess(origin, headers = form.headers))
        assertNotNull(form.profile(origin, null, false))
        form.rows[0] = ForwardAuthForm.Row("X-Proxy", "two")
        assertNull(form.profile(origin, null, false))
    }
}
