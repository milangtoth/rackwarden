package com.homelab.app.data.remote.dto.beszel

import com.homelab.app.R
import com.homelab.app.domain.model.OAuthException
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** How a Beszel server lets people sign in. */
@Serializable
data class BeszelAuthMethods(
    val password: Password? = null,
    val oauth2: OAuth2? = null
) {
    @Serializable
    data class Password(val enabled: Boolean = true)

    @Serializable
    data class OAuth2(val enabled: Boolean = false, val providers: List<Provider> = emptyList())

    @Serializable
    data class Provider(
        val name: String,
        val displayName: String? = null,
        val state: String = "",
        val authURL: String = "",
        val codeVerifier: String? = null
    ) {
        val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: name

        /** The provider's sign-in page. PocketBase leaves the redirect address for us to add. */
        fun signInUrl(): String {
            val url = authURL + URLEncoder.encode(OAuthCallback.REDIRECT_URL, "UTF-8")
            val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
            if (!authURL.endsWith("redirect_uri=") || scheme !in setOf("https", "http")) {
                throw OAuthException(R.string.oauth_invalid_reply)
            }
            return url
        }
    }

    val providers: List<Provider> get() = oauth2?.takeIf { it.enabled }?.providers.orEmpty()
    val allowsPassword: Boolean get() = password?.enabled ?: true
}

@Serializable
data class BeszelOAuthSession(val token: String, val record: Record? = null) {
    @Serializable
    data class Record(val email: String? = null)
}

/** The fixed address sign-in providers return to. It has to be registered with the provider. */
object OAuthCallback {
    const val SCHEME = "rackwarden"
    const val REDIRECT_URL = "rackwarden://oauth"

    /** Returns the authorization code, or throws when the reply is not the one that was asked for. */
    fun code(reply: String, state: String): String {
        val invalid = OAuthException(R.string.oauth_invalid_reply)
        val uri = runCatching { URI(reply) }.getOrNull() ?: throw invalid
        if (uri.scheme?.lowercase() != SCHEME || uri.host?.lowercase() != "oauth") throw invalid
        val items = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            it.substringBefore('=') to runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }
        fun value(name: String): String? = items.filter { it.first == name }.singleOrNull()?.second
        if (state.isEmpty() || value("state") != state) throw invalid
        if (items.any { it.first == "error" }) throw OAuthException(R.string.oauth_denied)
        return value("code")?.takeIf { it.isNotEmpty() } ?: throw invalid
    }
}
