package com.homelab.app.data.remote.access

import com.homelab.app.domain.model.AccessOrigin
import com.homelab.app.domain.model.AccessProfile
import com.homelab.app.util.GlobalEventBus
import com.homelab.app.util.ProxyExpired
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** The proxy in front of the service asks for a sign-in. */
class ForwardAuthRequiredException : IOException("Sign in to the proxy to continue")

/** Proxy credentials were held back because the connection is not verified. */
class ForwardAuthInsecureException : IOException("The connection is not secure enough for proxy credentials")

/** The address only redirects to its https version. */
class ForwardAuthUseHttpsException : IOException("This address redirects to https")

/** Which instance a request belongs to; the header itself is removed before sending. */
data class InstanceTag(val instanceId: String)

/** Must be the first interceptor, so later ones cannot lose the instance. */
class InstanceTagInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val id = request.header("X-Homelab-Instance-Id")?.takeIf { it.isNotBlank() }
            ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().tag(InstanceTag::class.java, InstanceTag(id)).build())
    }
}

/** Set on requests that carry proxy credentials; every redirect is checked against it. */
class ProxyGuard(val origin: AccessOrigin, private val onBlocked: () -> Unit) {
    fun check(request: Request) {
        val target = AccessOrigin.from(request.url.toString())
        val onProxyPage = ForwardAuth.isProxyPath(request.url.encodedPath)
        if (target == origin && !onProxyPage) return
        if (target != null && !onProxyPage && target.host == origin.host && target.isEncrypted &&
            !origin.isEncrypted && target.port == 443 && origin.port == 80
        ) {
            throw ForwardAuthUseHttpsException()
        }
        onBlocked()
        throw ForwardAuthRequiredException()
    }
}

/** Runs for every redirect, before anything is sent to the new address. */
class ProxyGuardInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        chain.request().tag(ProxyGuard::class.java)?.check(chain.request())
        return chain.proceed(chain.request())
    }
}

/**
 * Adds an instance's proxy credentials to requests for its own origin. Must be the last
 * application interceptor: the address is final by then, and login pages are seen first.
 */
class AccessInterceptor(
    private val store: AccessProfileStore,
    private val eventBus: GlobalEventBus,
    private val allowSelfSigned: Boolean
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origin = AccessOrigin.from(request.url.toString())
        val match = store.match(request.tag(InstanceTag::class.java)?.instanceId, origin)

        if (match == null) {
            val response = chain.proceed(request)
            if (ForwardAuth.isLoginPage(response, request)) {
                response.close()
                throw ForwardAuthRequiredException()
            }
            return response
        }

        val guard = ProxyGuard(match.access.origin) {
            match.instanceId?.let { eventBus.emitProxyExpired(ProxyExpired(it, match.access)) }
        }
        val prepared = prepare(request, match.profile, match.access.origin, allowSelfSigned)
            .newBuilder().tag(ProxyGuard::class.java, guard).build()
        val response = chain.proceed(prepared)
        // WebSocket handshakes do not follow redirects, so check where they point.
        if (response.code in 300..399) {
            response.header("Location")?.let { response.request.url.resolve(it) }?.let { target ->
                try {
                    guard.check(request.newBuilder().url(target).build())
                } catch (error: IOException) {
                    response.close()
                    throw error
                }
            }
        }
        return response
    }

    companion object {
        fun prepare(
            request: Request,
            profile: AccessProfile,
            origin: AccessOrigin,
            allowSelfSigned: Boolean,
            now: Long = System.currentTimeMillis()
        ): Request {
            val access = profile.access(origin) ?: return request
            if (!profile.allowsInsecureTransport && (!origin.isEncrypted || allowSelfSigned)) {
                throw ForwardAuthInsecureException()
            }
            val builder = request.newBuilder()
            // The service's own headers win over the proxy's.
            access.headers.filter { it.isValid && request.header(it.name) == null }
                .forEach { builder.header(it.name, it.value) }
            val active = access.cookies
                .filter { it.appliesTo(request.url.encodedPath, now) }
                .sortedByDescending { it.path.length }
            val own = request.headers("Cookie").joinToString("; ").ifEmpty { null }
            AccessProfile.mergeCookies(own, active)?.let { builder.header("Cookie", it) }
            return builder.build()
        }
    }
}
