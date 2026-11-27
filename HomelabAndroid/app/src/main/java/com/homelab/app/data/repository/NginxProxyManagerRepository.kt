package com.homelab.app.data.repository

import com.homelab.app.data.remote.api.NginxProxyManagerApi
import com.homelab.app.data.remote.dto.nginxpm.NpmAccessList
import com.homelab.app.data.remote.dto.nginxpm.NpmAccessListRequest
import com.homelab.app.data.remote.dto.nginxpm.NpmAuditLog
import com.homelab.app.data.remote.dto.nginxpm.NpmCertificate
import com.homelab.app.data.remote.dto.nginxpm.NpmCertificateRequest
import com.homelab.app.data.remote.dto.nginxpm.NpmDeadHost
import com.homelab.app.data.remote.dto.nginxpm.NpmDeadHostRequest
import com.homelab.app.data.remote.dto.nginxpm.NpmHostReport
import com.homelab.app.data.remote.dto.nginxpm.NpmProxyHost
import com.homelab.app.data.remote.dto.nginxpm.NpmProxyHostRequest
import com.homelab.app.data.remote.dto.nginxpm.NpmRedirectionHost
import com.homelab.app.data.remote.dto.nginxpm.NpmRedirectionHostRequest
import com.homelab.app.data.remote.dto.nginxpm.NpmSetting
import com.homelab.app.data.remote.dto.nginxpm.NpmStream
import com.homelab.app.data.remote.dto.nginxpm.NpmStreamRequest
import com.homelab.app.data.remote.dto.nginxpm.NpmUser
import com.homelab.app.data.remote.dto.nginxpm.NpmUserRequest
import com.homelab.app.data.remote.access.SignInTracker
import com.homelab.app.data.remote.dto.nginxpm.NpmTokenResult
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.domain.model.TokenLifetime
import com.homelab.app.domain.model.TwoFactorChallenge
import com.homelab.app.domain.model.TwoFactorInvalidException
import com.homelab.app.domain.model.TwoFactorRequiredException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NginxProxyManagerRepository @Inject constructor(
    private val api: NginxProxyManagerApi,
    private val serviceInstancesRepository: ServiceInstancesRepository,
    private val signInTracker: SignInTracker
) {
    /** NPMplus returns the token as a cookie instead of in the body. */
    private fun token(response: retrofit2.Response<NpmTokenResult>): String {
        val token = response.body()?.resolvedToken.orEmpty().ifBlank {
            response.headers().values("Set-Cookie")
                .map { it.substringBefore(';').trim() }
                .firstOrNull { it.startsWith("token=") }
                ?.substringAfter("token=").orEmpty()
        }
        return token.ifBlank { throw Exception("Authentication failed. Empty token received.") }
    }

    suspend fun completeTwoFactor(challenge: TwoFactorChallenge, code: String, allowSelfSigned: Boolean): String {
        val kind = challenge.kind as TwoFactorChallenge.Kind.Authenticator
        val response = api.authenticate(
            url = "${challenge.baseUrl}/api/tokens/2fa",
            allowSelfSigned = allowSelfSigned.toString(),
            credentials = mapOf("challenge_token" to kind.token, "code" to code)
        )
        if (response.code() in setOf(400, 401, 403)) throw TwoFactorInvalidException()
        if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code()}")
        return token(response)
    }

    /** A new token after a rejection, or null when the user has to sign in. */
    suspend fun renewSession(instance: ServiceInstance): String? {
        if (signInTracker.isRejected(instance.id, instance.token)) return null
        return try {
            authenticate(
                instance.url, instance.username.orEmpty(), instance.password.orEmpty(), instance.allowSelfSigned
            ).also { store(instance, it) }
        } catch (_: TwoFactorRequiredException) {
            signInTracker.markRejected(instance.id, instance.token)
            null
        }
    }

    /** Extends a valid session before it runs out, so codes are not asked for needlessly. */
    suspend fun renewIfAging(instance: ServiceInstance) {
        if (!TokenLifetime.shouldRenew(instance.token, 86_400.0)) return
        if (!signInTracker.mayTry("npm-renew-${instance.id}")) return
        for (base in listOfNotNull(instance.url, instance.fallbackUrl).map { it.trimEnd('/') }) {
            val response = runCatching {
                api.renewToken(
                    url = "$base/api/tokens", instanceId = instance.id,
                    allowSelfSigned = instance.allowSelfSigned.toString(),
                    authorization = "Bearer ${instance.token}", cookie = "token=${instance.token}"
                )
            }.getOrNull()?.takeIf { it.isSuccessful } ?: continue
            runCatching { token(response) }.getOrNull()?.let { store(instance, it) }
            return
        }
    }

    /** Only replaces the token it was made from, so a newer sign-in is kept. */
    private suspend fun store(from: ServiceInstance, token: String) {
        val current = serviceInstancesRepository.getInstance(from.id) ?: return
        if (current.token == from.token) serviceInstancesRepository.saveInstance(current.copy(token = token))
    }

    suspend fun authenticate(url: String, email: String, password: String, allowSelfSigned: Boolean = false): String {
        val cleanUrl = url.trimEnd('/') + "/api/tokens"
        try {
            val response = api.authenticate(
                url = cleanUrl,
                allowSelfSigned = allowSelfSigned.toString(),
                credentials = mapOf("identity" to email, "secret" to password)
            )
            
            if (!response.isSuccessful) {
                if (response.code() == 401 || response.code() == 403) {
                    throw Exception("Authentication failed. Check your email and password.")
                }
                throw Exception("HTTP Error: ${response.code()}")
            }
            
            val body = response.body() ?: throw Exception("Empty response body")
            if (body.requiresTwoFactor) {
                val challenge = body.challengeToken?.takeIf { it.isNotBlank() }
                    ?: throw Exception("Authentication failed. Empty token received.")
                throw TwoFactorRequiredException(
                    TwoFactorChallenge(url.trimEnd('/'), TwoFactorChallenge.Kind.Authenticator(challenge))
                )
            }
            return token(response)
        } catch (e: Exception) {
            if (e is retrofit2.HttpException && (e.code() == 401 || e.code() == 403)) {
                throw Exception("Authentication failed. Check your email and password.")
            }
            throw e
        }
    }

    suspend fun getHostReport(instanceId: String): NpmHostReport {
        return api.getHostReport(instanceId = instanceId)
    }

    suspend fun getProxyHosts(instanceId: String): List<NpmProxyHost> {
        return api.getProxyHosts(instanceId = instanceId)
    }

    suspend fun createProxyHost(instanceId: String, request: NpmProxyHostRequest): NpmProxyHost {
        return api.createProxyHost(instanceId = instanceId, request = request)
    }

    suspend fun updateProxyHost(instanceId: String, hostId: Int, request: NpmProxyHostRequest): NpmProxyHost {
        return api.updateProxyHost(instanceId = instanceId, hostId = hostId, request = request)
    }

    suspend fun deleteProxyHost(instanceId: String, hostId: Int) {
        api.deleteProxyHost(instanceId = instanceId, hostId = hostId)
    }

    suspend fun enableProxyHost(instanceId: String, hostId: Int) {
        api.enableProxyHost(instanceId = instanceId, hostId = hostId)
    }

    suspend fun disableProxyHost(instanceId: String, hostId: Int) {
        api.disableProxyHost(instanceId = instanceId, hostId = hostId)
    }

    suspend fun getRedirectionHosts(instanceId: String): List<NpmRedirectionHost> {
        return api.getRedirectionHosts(instanceId = instanceId)
    }

    suspend fun createRedirectionHost(instanceId: String, request: NpmRedirectionHostRequest): NpmRedirectionHost {
        return api.createRedirectionHost(instanceId = instanceId, request = request)
    }

    suspend fun updateRedirectionHost(instanceId: String, hostId: Int, request: NpmRedirectionHostRequest): NpmRedirectionHost {
        return api.updateRedirectionHost(instanceId = instanceId, hostId = hostId, request = request)
    }

    suspend fun deleteRedirectionHost(instanceId: String, hostId: Int) {
        api.deleteRedirectionHost(instanceId = instanceId, hostId = hostId)
    }

    suspend fun getStreams(instanceId: String): List<NpmStream> {
        return api.getStreams(instanceId = instanceId)
    }

    suspend fun createStream(instanceId: String, request: NpmStreamRequest): NpmStream {
        return api.createStream(instanceId = instanceId, request = request)
    }

    suspend fun updateStream(instanceId: String, streamId: Int, request: NpmStreamRequest): NpmStream {
        return api.updateStream(instanceId = instanceId, streamId = streamId, request = request)
    }

    suspend fun deleteStream(instanceId: String, streamId: Int) {
        api.deleteStream(instanceId = instanceId, streamId = streamId)
    }

    suspend fun getDeadHosts(instanceId: String): List<NpmDeadHost> {
        return api.getDeadHosts(instanceId = instanceId)
    }

    suspend fun createDeadHost(instanceId: String, request: NpmDeadHostRequest): NpmDeadHost {
        return api.createDeadHost(instanceId = instanceId, request = request)
    }

    suspend fun updateDeadHost(instanceId: String, hostId: Int, request: NpmDeadHostRequest): NpmDeadHost {
        return api.updateDeadHost(instanceId = instanceId, hostId = hostId, request = request)
    }

    suspend fun deleteDeadHost(instanceId: String, hostId: Int) {
        api.deleteDeadHost(instanceId = instanceId, hostId = hostId)
    }

    suspend fun getCertificates(instanceId: String): List<NpmCertificate> {
        return api.getCertificates(instanceId = instanceId)
    }

    suspend fun createCertificate(instanceId: String, request: NpmCertificateRequest): NpmCertificate {
        return api.createCertificate(instanceId = instanceId, request = request)
    }

    suspend fun deleteCertificate(instanceId: String, certId: Int) {
        api.deleteCertificate(instanceId = instanceId, certId = certId)
    }

    suspend fun renewCertificate(instanceId: String, certId: Int): NpmCertificate {
        return api.renewCertificate(instanceId = instanceId, certId = certId)
    }

    suspend fun getAccessLists(instanceId: String): List<NpmAccessList> {
        return api.getAccessLists(instanceId = instanceId)
    }

    suspend fun createAccessList(instanceId: String, request: NpmAccessListRequest): NpmAccessList {
        return api.createAccessList(instanceId = instanceId, request = request)
    }

    suspend fun updateAccessList(instanceId: String, id: Int, request: NpmAccessListRequest): NpmAccessList {
        return api.updateAccessList(instanceId = instanceId, listId = id, request = request)
    }

    suspend fun deleteAccessList(instanceId: String, id: Int) {
        api.deleteAccessList(instanceId = instanceId, listId = id)
    }

    suspend fun getUsers(instanceId: String): List<NpmUser> {
        return api.getUsers(instanceId = instanceId)
    }

    suspend fun createUser(instanceId: String, request: NpmUserRequest): NpmUser {
        return api.createUser(instanceId = instanceId, request = request)
    }

    suspend fun updateUser(instanceId: String, userId: Int, request: NpmUserRequest): NpmUser {
        return api.updateUser(instanceId = instanceId, userId = userId, request = request)
    }

    suspend fun deleteUser(instanceId: String, userId: Int) {
        api.deleteUser(instanceId = instanceId, userId = userId)
    }

    suspend fun getAuditLogs(instanceId: String): List<NpmAuditLog> {
        return api.getAuditLogs(instanceId = instanceId)
    }

    suspend fun getSettings(instanceId: String): List<NpmSetting> {
        return api.getSettings(instanceId = instanceId)
    }
}
