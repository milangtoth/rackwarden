package com.homelab.app.data.repository

import com.homelab.app.data.remote.api.BeszelApi
import com.homelab.app.data.remote.dto.beszel.BeszelContainerRecord
import com.homelab.app.data.remote.dto.beszel.BeszelContainerStatsRecord
import com.homelab.app.data.remote.dto.beszel.BeszelSystem
import com.homelab.app.data.remote.dto.beszel.BeszelSystemDetails
import com.homelab.app.data.remote.dto.beszel.BeszelSystemRecord
import com.homelab.app.data.remote.dto.beszel.BeszelSmartDevice
import com.homelab.app.R
import com.homelab.app.data.remote.access.SignInTracker
import com.homelab.app.data.remote.dto.beszel.BeszelAuthMethods
import com.homelab.app.data.remote.dto.beszel.BeszelOAuthSession
import com.homelab.app.data.remote.dto.beszel.OAuthCallback
import com.homelab.app.domain.model.OAuthException
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.domain.model.SignInRequiredException
import com.homelab.app.domain.model.TokenLifetime
import com.homelab.app.domain.model.TwoFactorChallenge
import com.homelab.app.domain.model.TwoFactorInvalidException
import com.homelab.app.domain.model.TwoFactorRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BeszelRepository @Inject constructor(
    private val api: BeszelApi,
    private val serviceInstancesRepository: ServiceInstancesRepository,
    private val signInTracker: SignInTracker
) {
    suspend fun authenticate(
        url: String,
        email: String,
        password: String,
        fallbackUrl: String? = null,
        allowSelfSigned: Boolean = false
    ): String {
        val candidates = listOf(url, fallbackUrl.orEmpty())
            .mapNotNull { raw -> raw.trim().trimEnd('/').takeIf { it.isNotBlank() } }
            .distinct()
        var lastError: Exception? = null
        for (candidate in candidates) {
            val cleanUrl = "$candidate/api/collections/users/auth-with-password"
            try {
                val response = api.postSignIn(
                    url = cleanUrl,
                    allowSelfSigned = allowSelfSigned.toString(),
                    body = mapOf("identity" to email, "password" to password)
                )
                if (response.isSuccessful) {
                    return json.decodeFromString<BeszelOAuthSession>(response.body()?.string().orEmpty()).token
                }
                mfaId(response)?.let {
                    // The email is only requested once the user is there to read it.
                    throw TwoFactorRequiredException(
                        TwoFactorChallenge(candidate, TwoFactorChallenge.Kind.Email(email, it))
                    )
                }
                throw retrofit2.HttpException(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TwoFactorRequiredException) {
                throw e
            } catch (e: Exception) {
                if (e is retrofit2.HttpException && e.code() == 400) {
                    // Pocketbase often throws 400 for bad auth
                    lastError = Exception("Authentication failed. Check your credentials and URL.")
                } else {
                    lastError = e
                }
            }
        }
        throw lastError ?: Exception("Authentication failed. Check your credentials and URL.")
    }

    suspend fun getSystems(instanceId: String): List<BeszelSystem> {
        val response = api.getSystems(instanceId = instanceId)
        val before = serviceInstancesRepository.getInstance(instanceId)
        val systems = response.items.sortedBy { it.name.lowercase() }
        if (systems.isNotEmpty()) return systems

        // PocketBase/Beszel can occasionally return an empty authorized collection after
        // an expired token instead of a hard 401/403. Retry once after refreshing.
        val refreshedToken = try {
            renewSession(instanceId, emptyResult = true)
        } catch (e: TwoFactorRequiredException) {
            null
        }
        if (before != null && signInTracker.isRejected(instanceId, before.token)) throw SignInRequiredException()
        if (refreshedToken.isNullOrBlank()) return systems
        return api.getSystems(instanceId = instanceId).items.sortedBy { it.name.lowercase() }
    }

    suspend fun getSystem(instanceId: String, id: String): BeszelSystem {
        return api.getSystem(instanceId = instanceId, id = id)
    }

    suspend fun getSystemDetails(instanceId: String, systemId: String): BeszelSystemDetails? {
        val filter = "system='$systemId'"
        val response = api.getSystemDetails(instanceId = instanceId, filter = filter, limit = 1)
        return response.items.firstOrNull()
    }

    suspend fun getSystemRecords(instanceId: String, systemId: String, limit: Int = 60): List<BeszelSystemRecord> {
        val filter = "system='$systemId'"
        val response = api.getSystemRecords(instanceId = instanceId, filter = filter, limit = limit)
        return response.items
    }

    suspend fun getSmartDevices(instanceId: String, systemId: String, limit: Int = 10): List<BeszelSmartDevice> {
        val filter = "system='$systemId'"
        val response = api.getSmartDevices(instanceId = instanceId, filter = filter, limit = limit)
        return response.items
    }

    suspend fun getContainers(instanceId: String, systemId: String): List<BeszelContainerRecord> {
        val filter = "system='$systemId'"
        val response = api.getContainers(instanceId = instanceId, filter = filter)
        return response.items
    }

    suspend fun getContainerStats(instanceId: String, systemId: String, limit: Int = 240): List<BeszelContainerStatsRecord> {
        val filter = "system='$systemId'"
        val response = api.getContainerStats(instanceId = instanceId, filter = filter, limit = limit)
        return response.items
    }

    suspend fun getContainerLogs(instanceId: String, token: String, systemId: String, containerId: String): String {
        val response = api.getContainerLogs(
            instanceId = instanceId,
            authorization = "Bearer $token",
            systemId = systemId,
            containerId = containerId
        )
        val raw = response.string()
        return formatLogs(raw)
    }

    suspend fun getContainerInfo(instanceId: String, token: String, systemId: String, containerId: String): String {
        val response = api.getContainerInfo(
            instanceId = instanceId,
            authorization = "Bearer $token",
            systemId = systemId,
            containerId = containerId
        )
        val raw = response.string()
        return try {
            val json = org.json.JSONObject(raw)
            json.toString(2)
        } catch (_: Exception) {
            raw
        }
    }

    private fun formatLogs(raw: String): String {
        return try {
            val json = org.json.JSONObject(raw)
            val logs = json.optString("logs", "")
            if (logs.isNotEmpty()) {
                logs
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
            } else {
                json.toString(2)
            }
        } catch (_: Exception) {
            raw
        }
    }

    // MARK: - OAuth and two-factor codes

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable
    private data class Failure(val mfaId: String? = null, val message: String? = null, val status: Int? = null)

    private fun failure(response: retrofit2.Response<*>): Failure =
        runCatching { json.decodeFromString<Failure>(response.errorBody()?.string().orEmpty()) }.getOrDefault(Failure())

    private fun mfaId(response: retrofit2.Response<*>): String? =
        if (response.code() == 401) failure(response).mfaId?.takeIf { it.isNotBlank() } else null

    /** The sign-in methods and the address they came from; the code is exchanged at the same one. */
    suspend fun authMethods(url: String, fallbackUrl: String?, allowSelfSigned: Boolean): Pair<String, BeszelAuthMethods> {
        var firstError: Exception? = null
        for (base in listOfNotNull(url, fallbackUrl).map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()) {
            try {
                return base to api.getAuthMethods(
                    url = "$base/api/collections/users/auth-methods", allowSelfSigned = allowSelfSigned.toString()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: OAuthException(R.string.oauth_unavailable)
    }

    /** @param email only needed when the server also asks for an emailed code. */
    suspend fun completeOAuth(
        provider: BeszelAuthMethods.Provider, code: String, baseUrl: String, email: String?, allowSelfSigned: Boolean
    ): BeszelOAuthSession {
        val response = api.postSignIn(
            url = "$baseUrl/api/collections/users/auth-with-oauth2",
            allowSelfSigned = allowSelfSigned.toString(),
            body = mapOf(
                "provider" to provider.name,
                "code" to code,
                "codeVerifier" to provider.codeVerifier.orEmpty(),
                "redirectURL" to OAuthCallback.REDIRECT_URL
            )
        )
        if (response.isSuccessful) return json.decodeFromString(response.body()?.string().orEmpty())
        val failure = failure(response)
        if (response.code() == 401 && !failure.mfaId.isNullOrBlank()) {
            if (email.isNullOrBlank()) throw OAuthException(R.string.oauth_email_needed)
            throw TwoFactorRequiredException(
                TwoFactorChallenge(baseUrl, TwoFactorChallenge.Kind.Email(email, failure.mfaId))
            )
        }
        throw OAuthException(R.string.oauth_rejected, failure.message ?: "HTTP ${response.code()}")
    }

    /** Emails a code and returns the challenge to complete with it. */
    suspend fun requestTwoFactorCode(challenge: TwoFactorChallenge, allowSelfSigned: Boolean): TwoFactorChallenge {
        val kind = challenge.kind as TwoFactorChallenge.Kind.Email
        val response = api.postSignIn(
            url = "${challenge.baseUrl}/api/collections/users/request-otp",
            allowSelfSigned = allowSelfSigned.toString(),
            body = mapOf("email" to kind.address)
        )
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        val otpId = json.decodeFromString<OtpReply>(response.body()?.string().orEmpty()).otpId
        return challenge.copy(kind = kind.copy(otpId = otpId))
    }

    @Serializable
    private data class OtpReply(val otpId: String)

    suspend fun completeTwoFactor(challenge: TwoFactorChallenge, code: String, allowSelfSigned: Boolean): String {
        val kind = challenge.kind as TwoFactorChallenge.Kind.Email
        val response = api.postSignIn(
            url = "${challenge.baseUrl}/api/collections/users/auth-with-otp",
            allowSelfSigned = allowSelfSigned.toString(),
            body = mapOf("otpId" to kind.otpId.orEmpty(), "password" to code, "mfaId" to kind.mfaId)
        )
        if (response.code() in setOf(400, 401)) throw TwoFactorInvalidException()
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        return json.decodeFromString<BeszelOAuthSession>(response.body()?.string().orEmpty()).token
    }

    /**
     * Gets a working session after a rejection, or marks the instance as needing the user.
     * @param emptyResult the session only looks rejected, because a list came back empty.
     */
    suspend fun renewSession(instanceId: String, emptyResult: Boolean = false): String? {
        val instance = serviceInstancesRepository.getInstance(instanceId) ?: return null
        if (signInTracker.isRejected(instanceId, instance.token)) return null
        if (!instance.password.isNullOrBlank() && !instance.username.isNullOrBlank()) {
            return try {
                refreshStoredToken(instanceId)
            } catch (e: TwoFactorRequiredException) {
                if (!emptyResult || TokenLifetime.isExpired(instance.token)) {
                    signInTracker.markRejected(instanceId, instance.token)
                }
                null
            }
        }
        // A server without systems also answers with an empty list.
        if (emptyResult && !signInTracker.mayTry("beszel-empty-$instanceId")) return null
        return checkSession(instance)
    }

    /** Without a password (OAuth), a session can only be renewed or handed to the user. */
    private suspend fun checkSession(instance: ServiceInstance): String? {
        if (instance.token.isBlank()) return null
        if (!TokenLifetime.isExpired(instance.token)) {
            var rejected = false
            for (base in listOfNotNull(instance.url, instance.fallbackUrl).map { it.trimEnd('/') }) {
                val response = try {
                    api.postSignIn(
                        url = "$base/api/collections/users/auth-refresh",
                        allowSelfSigned = instance.allowSelfSigned.toString(),
                        instanceId = instance.id,
                        authorization = "Bearer ${instance.token}"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                if (response.isSuccessful) {
                    val token = json.decodeFromString<BeszelOAuthSession>(response.body()?.string().orEmpty()).token
                    storeToken(instance, token)
                    return token
                }
                // Only PocketBase's own answer counts, not one from a proxy in front of it.
                if (response.code() == 401 && failure(response).status == 401) {
                    rejected = true
                    break
                }
            }
            if (!rejected) return null
        }
        signInTracker.markRejected(instance.id, instance.token)
        return null
    }

    /** Extends a valid session before it runs out, so codes are not asked for needlessly. */
    suspend fun renewIfAging(instance: ServiceInstance) {
        if (!TokenLifetime.shouldRenew(instance.token, 604_800.0)) return
        if (!signInTracker.mayTry("beszel-renew-${instance.id}")) return
        checkSession(instance)
    }

    /** Only replaces the token it was made from, so a newer sign-in is kept. */
    private suspend fun storeToken(from: ServiceInstance, token: String) {
        val current = serviceInstancesRepository.getInstance(from.id) ?: return
        if (current.token == from.token) serviceInstancesRepository.saveInstance(current.copy(token = token))
    }

    suspend fun refreshStoredToken(instanceId: String): String? {
        val instance = serviceInstancesRepository.getInstance(instanceId) ?: return null
        val email = instance.username?.takeIf { it.isNotBlank() } ?: return null
        val password = instance.password?.takeIf { it.isNotBlank() } ?: return null
        val newToken = authenticate(
            url = instance.url,
            email = email,
            password = password,
            fallbackUrl = instance.fallbackUrl,
            allowSelfSigned = instance.allowSelfSigned
        )
        storeToken(instance, newToken)
        return newToken
    }
}
