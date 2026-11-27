package com.homelab.app.domain.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

/** Scheme + host + port. `ws`/`wss` are treated as `http`/`https`. */
@Serializable
data class AccessOrigin(val scheme: String, val host: String, val port: Int) {
    val isEncrypted: Boolean get() = scheme == "https"

    companion object {
        fun from(raw: String?): AccessOrigin? {
            val uri = runCatching { URI(raw.orEmpty().trim()) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val scheme = when (uri.scheme?.lowercase()) {
                "http", "ws" -> "http"
                "https", "wss" -> "https"
                else -> return null
            }
            return AccessOrigin(scheme, host, if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80)
        }
    }
}

@Serializable
data class AccessHeader(val name: String, val value: String) {
    val isValid: Boolean
        get() = name.isNotEmpty() &&
            name.all { it in TOKEN } &&
            name.lowercase() !in RESERVED &&
            value.isNotEmpty() &&
            value.none { it.code < 32 || it.code > 126 }

    private companion object {
        const val TOKEN = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        /** Headers the HTTP stack, the cookie merger or the app itself own. */
        val RESERVED = setOf(
            "host", "cookie", "content-length", "content-type", "connection",
            "transfer-encoding", "upgrade", "origin", "te", "trailer"
        )
    }
}

@Serializable
data class AccessCookie(
    val name: String,
    val value: String,
    val path: String = "/",
    /** Milliseconds since 1970, or null for a session cookie. */
    val expiresAt: Long? = null
) {
    fun appliesTo(requestPath: String, now: Long): Boolean {
        if (expiresAt != null && expiresAt <= now) return false
        val current = requestPath.ifEmpty { "/" }
        if (!current.startsWith(path)) return false
        return path.endsWith("/") || current.length == path.length || current[path.length] == '/'
    }
}

/** Credentials for the proxy in front of a single origin. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OriginAccess(
    val origin: AccessOrigin,
    @EncodeDefault val headers: List<AccessHeader> = emptyList(),
    @EncodeDefault val cookies: List<AccessCookie> = emptyList()
)

@Serializable
enum class AccessMethod {
    @SerialName("browser") BROWSER,
    @SerialName("headers") HEADERS
}

/**
 * How to get past an authenticating proxy before talking to a service.
 * The JSON names match the iOS app, so backups work on both.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AccessProfile(
    @EncodeDefault val isEnabled: Boolean = false,
    @EncodeDefault val method: AccessMethod = AccessMethod.BROWSER,
    @EncodeDefault val usesNativeLogin: Boolean = true,
    /** Explicit consent to send proxy credentials without verified TLS. */
    @EncodeDefault val allowsInsecureTransport: Boolean = false,
    @EncodeDefault val origins: List<OriginAccess> = emptyList()
) {
    fun access(origin: AccessOrigin?): OriginAccess? =
        if (isEnabled && origin != null) origins.firstOrNull { it.origin == origin } else null

    /** Drops credentials for origins the instance no longer uses. */
    fun limitedTo(urls: List<String?>): AccessProfile {
        val allowed = urls.mapNotNull { AccessOrigin.from(it) }.toSet()
        return copy(origins = origins.filter { it.origin in allowed })
    }

    /** Safe to export: never contains session cookies. */
    val withoutCookies: AccessProfile
        get() = copy(origins = origins.map { OriginAccess(it.origin, it.headers) })

    companion object {
        /** The service's own cookies win on name conflicts. */
        fun mergeCookies(existing: String?, cookies: List<AccessCookie>): String? {
            val pairs = existing.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            val names = pairs.map { it.substringBefore('=') }.toMutableSet()
            cookies.forEach { if (names.add(it.name)) pairs += "${it.name}=${it.value}" }
            return pairs.joinToString("; ").ifEmpty { null }
        }
    }
}
