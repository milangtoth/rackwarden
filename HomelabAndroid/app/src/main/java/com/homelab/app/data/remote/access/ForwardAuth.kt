package com.homelab.app.data.remote.access

import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.domain.model.AccessHeader
import com.homelab.app.domain.model.AccessOrigin
import com.homelab.app.domain.model.AccessProfile
import com.homelab.app.domain.model.OriginAccess
import com.homelab.app.util.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

object ForwardAuth {
    fun isProxyPath(path: String): Boolean =
        path == "/outpost.goauthentik.io" || path.startsWith("/outpost.goauthentik.io/") ||
            path.startsWith("/cdn-cgi/access/") ||
            path in setOf("/oauth2/start", "/oauth2/sign_in", "/oauth2/callback")

    /** True for a page of the service itself, not of the proxy living on the same address. */
    fun isServicePage(url: String?, origin: AccessOrigin): Boolean {
        if (AccessOrigin.from(url?.substringBefore('#')?.substringBefore('?')) != origin) return false
        val path = runCatching { java.net.URI(url!!.substringBefore('#').substringBefore('?')).path }.getOrNull()
        return !isProxyPath(path.orEmpty())
    }

    /** True when a request without proxy credentials ended up on a sign-in page. */
    fun isLoginPage(response: Response, requested: Request): Boolean {
        val final = response.request.url
        if (isProxyPath(final.encodedPath)) return true
        return final.host != requested.url.host &&
            response.header("Content-Type")?.lowercase()?.startsWith("text/html") == true
    }

    private val usesOwnHeaders = setOf(
        ServiceType.PIHOLE, ServiceType.TECHNITIUM, ServiceType.DOCKHAND, ServiceType.KOMODO, ServiceType.MALTRAIL,
        ServiceType.QBITTORRENT, ServiceType.RADARR, ServiceType.SONARR, ServiceType.LIDARR, ServiceType.PLEX,
        ServiceType.HEALTHCHECKS, ServiceType.JELLYSTAT, ServiceType.UNIFI_NETWORK, ServiceType.TRUENAS,
        ServiceType.SCRUTINY, ServiceType.DOCKGE
    )

    /** Services that use `Authorization` themselves cannot share it with the proxy. */
    fun canSend(header: AccessHeader, type: ServiceType, usesNativeLogin: Boolean): Boolean =
        header.isValid && !header.name.startsWith("X-Homelab-", ignoreCase = true) &&
            (!header.name.equals("Authorization", ignoreCase = true) || !usesNativeLogin || type in usesOwnHeaders)

    /** Services that can work with the proxy as their only sign-in. */
    fun canSkipNativeLogin(type: ServiceType): Boolean = type in setOf(
        ServiceType.GLUETUN, ServiceType.FLARESOLVERR, ServiceType.MALTRAIL, ServiceType.UPTIME_KUMA,
        ServiceType.ADGUARD_HOME, ServiceType.QBITTORRENT, ServiceType.SCRUTINY, ServiceType.DOCKGE
    )

    /** What a pair of answers, without and with the credentials, says about them. */
    fun outcome(anonymous: Int?, authenticated: Int?): ProxyOutcome {
        if (anonymous != null && anonymous in 200..399) return ProxyOutcome.NOT_NEEDED
        if (authenticated == null) return ProxyOutcome.BLOCKED
        if (authenticated in 200..399) return ProxyOutcome.PASSED
        if (authenticated >= 500 || authenticated == 407) return ProxyOutcome.BLOCKED
        if (anonymous == null) return ProxyOutcome.PASSED
        // The proxy's refusal gave way to the service's own answer.
        val refused = setOf(401, 403)
        return if (anonymous in refused && authenticated !in refused) ProxyOutcome.PASSED else ProxyOutcome.BLOCKED
    }
}

enum class ProxyOutcome {
    /** The credentials get past the proxy. */
    PASSED,

    /** The proxy still asks for a sign-in. */
    BLOCKED,

    /** The service answers without any proxy credentials. */
    NOT_NEEDED
}

/** Asks an address for its front page, with exactly the given credentials. */
@Singleton
class ForwardAuthProber @Inject constructor(private val clients: TlsClientSelector) {

    suspend fun probe(
        url: String, access: OriginAccess, allowsInsecure: Boolean, allowSelfSigned: Boolean
    ): ProxyOutcome = withContext(Dispatchers.IO) {
        val anonymous = status(url, OriginAccess(access.origin), allowsInsecure, allowSelfSigned)
        if (anonymous != null && anonymous in 200..399) return@withContext ProxyOutcome.NOT_NEEDED
        ForwardAuth.outcome(anonymous, status(url, access, allowsInsecure, allowSelfSigned))
    }

    /** True when the address asks for a proxy sign-in. Sends no credentials. */
    suspend fun isProtected(url: String, allowSelfSigned: Boolean): Boolean = withContext(Dispatchers.IO) {
        val origin = AccessOrigin.from(url) ?: return@withContext false
        runCatching { status(url, OriginAccess(origin), true, allowSelfSigned) == null }.getOrDefault(false)
    }

    /** The HTTP status, or null when the proxy asks for a sign-in. */
    private fun status(url: String, access: OriginAccess, allowsInsecure: Boolean, allowSelfSigned: Boolean): Int? {
        val profile = AccessProfile(isEnabled = true, allowsInsecureTransport = allowsInsecure, origins = listOf(access))
        val request = AccessInterceptor.prepare(Request.Builder().url(url).build(), profile, access.origin, allowSelfSigned)
            .newBuilder().tag(ProxyGuard::class.java, ProxyGuard(access.origin) {}).build()
        return try {
            bare(clients.forAllowSelfSigned(allowSelfSigned)).newCall(request).execute().use { it.code }
        } catch (_: ForwardAuthRequiredException) {
            null
        }
    }

    /** Keeps TLS settings and the redirect guard, but nothing that adds stored credentials. */
    private fun bare(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .apply { interceptors().clear() }
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}
