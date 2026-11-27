package com.homelab.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64

/**
 * A login that accepted the password and still needs a one-time code.
 * Held in memory only; codes and challenges are never stored.
 */
data class TwoFactorChallenge(val baseUrl: String, val kind: Kind) {
    sealed interface Kind {
        /** Code from an authenticator app, or a backup code. */
        data class Authenticator(val token: String) : Kind

        /** Code sent by email. [otpId] is set once the user asked for the email. */
        data class Email(val address: String, val mfaId: String, val otpId: String? = null) : Kind
    }
}

class TwoFactorRequiredException(val challenge: TwoFactorChallenge) : Exception("A two-factor code is required")
class TwoFactorInvalidException : Exception("The code was rejected or has expired")

/** The session ended and only the user can start a new one. */
class SignInRequiredException : IOException("Sign in again to continue")

/** The provider's reply to an OAuth sign-in is unusable; the reason is a string resource. */
class OAuthException(val messageRes: Int, val detail: String? = null) : Exception("OAuth sign-in failed")

object TokenLifetime {
    @Serializable
    private data class Claims(val iat: Double? = null, val exp: Double? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun isExpired(token: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val exp = claims(token)?.exp ?: return false
        return exp <= nowMs / 1000.0
    }

    /** True in the second half of a still-valid JWT's life. */
    fun shouldRenew(token: String, assumedLifetimeSeconds: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        val claims = claims(token) ?: return false
        val exp = claims.exp ?: return false
        val remaining = exp - nowMs / 1000.0
        val lifetime = claims.iat?.let { exp - it } ?: assumedLifetimeSeconds
        return remaining > 0 && remaining < lifetime / 2
    }

    private fun claims(token: String): Claims? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        return runCatching {
            json.decodeFromString<Claims>(String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8))
        }.getOrNull()
    }
}
