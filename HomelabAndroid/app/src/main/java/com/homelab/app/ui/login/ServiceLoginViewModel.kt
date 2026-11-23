package com.homelab.app.ui.login

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homelab.app.R
import com.homelab.app.data.repository.BeszelRepository
import com.homelab.app.data.repository.DockhandRepository
import com.homelab.app.data.repository.DockmonRepository
import com.homelab.app.data.repository.GiteaRepository
import com.homelab.app.data.repository.LinuxUpdateRepository
import com.homelab.app.data.repository.CraftyRepository
import com.homelab.app.data.repository.HealthchecksRepository
import com.homelab.app.data.repository.JellystatRepository
import com.homelab.app.data.repository.KomodoRepository
import com.homelab.app.data.repository.MaltrailRepository
import com.homelab.app.data.repository.MediaArrRepository
import com.homelab.app.data.repository.AdGuardHomeRepository
import com.homelab.app.data.repository.NginxProxyManagerRepository
import com.homelab.app.data.repository.PatchmonRepository
import com.homelab.app.data.repository.PangolinRepository
import com.homelab.app.data.repository.PiholeRepository
import com.homelab.app.data.repository.PlexRepository
import com.homelab.app.data.repository.PortainerRepository
import com.homelab.app.data.repository.ServiceInstancesRepository
import com.homelab.app.data.repository.ServicesRepository
import com.homelab.app.data.repository.TechnitiumRepository
import com.homelab.app.data.repository.TrueNasRepository
import com.homelab.app.data.repository.UnifiRepository
import com.homelab.app.data.repository.UptimeKumaRepository
import com.homelab.app.data.repository.WakapiRepository
import com.homelab.app.data.repository.ProxmoxRepository
import com.homelab.app.data.repository.PterodactylRepository
import com.homelab.app.data.repository.CalagopusRepository
import com.homelab.app.data.repository.DockgeLoginRequiredException
import com.homelab.app.data.repository.DockgeRepository
import com.homelab.app.data.repository.ScrutinyRepository
import com.homelab.app.data.remote.access.AccessProfileStore
import com.homelab.app.data.remote.access.ForwardAuth
import com.homelab.app.data.remote.access.ForwardAuthProber
import com.homelab.app.data.remote.access.ProxyOutcome
import com.homelab.app.data.remote.dto.beszel.BeszelAuthMethods
import com.homelab.app.data.remote.dto.beszel.OAuthCallback
import com.homelab.app.domain.model.AccessMethod
import com.homelab.app.domain.model.AccessOrigin
import com.homelab.app.domain.model.OAuthException
import com.homelab.app.domain.model.OriginAccess
import com.homelab.app.domain.model.TwoFactorChallenge
import com.homelab.app.domain.model.TwoFactorRequiredException
import com.homelab.app.ui.forwardauth.ForwardAuthForm
import com.homelab.app.domain.model.PiHoleAuthMode
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.util.ErrorHandler
import com.homelab.app.util.ServiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@HiltViewModel
class ServiceLoginViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val servicesRepository: ServicesRepository,
    private val serviceInstancesRepository: ServiceInstancesRepository,
    private val portainerRepository: PortainerRepository,
    private val piholeRepository: PiholeRepository,
    private val adGuardHomeRepository: AdGuardHomeRepository,
    private val beszelRepository: BeszelRepository,
    private val giteaRepository: GiteaRepository,
    private val linuxUpdateRepository: LinuxUpdateRepository,
    private val craftyRepository: CraftyRepository,
    private val technitiumRepository: TechnitiumRepository,
    private val dockhandRepository: DockhandRepository,
    private val dockmonRepository: DockmonRepository,
    private val komodoRepository: KomodoRepository,
    private val maltrailRepository: MaltrailRepository,
    private val uptimeKumaRepository: UptimeKumaRepository,
    private val unifiRepository: UnifiRepository,
    private val nginxProxyManagerRepository: NginxProxyManagerRepository,
    private val healthchecksRepository: HealthchecksRepository,
    private val jellystatRepository: JellystatRepository,
    private val patchmonRepository: PatchmonRepository,
    private val pangolinRepository: PangolinRepository,
    private val plexRepository: PlexRepository,
    private val mediaArrRepository: MediaArrRepository,
    private val wakapiRepository: WakapiRepository,
    private val proxmoxRepository: ProxmoxRepository,
    private val trueNasRepository: TrueNasRepository,
    private val pterodactylRepository: PterodactylRepository,
    private val calagopusRepository: CalagopusRepository,
    private val scrutinyRepository: ScrutinyRepository,
    private val dockgeRepository: DockgeRepository,
    private val accessProfileStore: AccessProfileStore,
    private val forwardAuthProber: ForwardAuthProber
) : ViewModel() {

    private val existingInstanceId: String? = savedStateHandle["instanceId"]

    private val _existingInstance = MutableStateFlow<ServiceInstance?>(null)
    val existingInstance: StateFlow<ServiceInstance?> = _existingInstance

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** Also the ID of a new instance, so its browser session is kept when it is saved. */
    val draftId: String = existingInstanceId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    val forwardAuth = ForwardAuthForm()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    private val _twoFactorPrompt = MutableStateFlow<TwoFactorPrompt?>(null)
    val twoFactorPrompt: StateFlow<TwoFactorPrompt?> = _twoFactorPrompt

    private val _offersForwardAuth = MutableStateFlow(false)
    val offersForwardAuth: StateFlow<Boolean> = _offersForwardAuth

    private val _oauthMethods = MutableStateFlow<BeszelAuthMethods?>(null)
    val oauthMethods: StateFlow<BeszelAuthMethods?> = _oauthMethods
    private var oauthBaseUrl = ""

    /** The sign-in page to open in the browser. */
    private val _oauthPage = MutableStateFlow<String?>(null)
    val oauthPage: StateFlow<String?> = _oauthPage

    /** True while more than one provider has to be chosen from. */
    private val _choosesProvider = MutableStateFlow(false)
    val choosesProvider: StateFlow<Boolean> = _choosesProvider

    private var lastUrls: List<String?> = emptyList()

    init {
        forwardAuth.load(accessProfileStore.profile(draftId))
        if (!existingInstanceId.isNullOrBlank()) {
            viewModelScope.launch {
                _existingInstance.value = serviceInstancesRepository.getInstance(existingInstanceId)
                    ?.also { lastUrls = listOf(it.url, it.fallbackUrl) }
            }
        }
    }

    override fun onCleared() {
        accessProfileStore.discardDraft(draftId)
        OAuthReplies.cancel()
        servicesRepository.removeWebDataUnlessUsed(draftId, lastUrls)
    }

    fun urlFor(raw: String): String? = cleanOptionalUrl(raw)

    fun skipsNativeLogin(type: ServiceType): Boolean =
        forwardAuth.isOn && forwardAuth.skipsNativeLogin && ForwardAuth.canSkipNativeLogin(type)

    /** Test logins use the entered proxy credentials before anything is saved. */
    private fun applyDraft(type: ServiceType, url: String, fallbackUrl: String?, allowSelfSigned: Boolean) {
        val urls = listOf(url, fallbackUrl)
        lastUrls = urls
        if (!forwardAuth.isOn) {
            accessProfileStore.setDraft(draftId, null, urls)
            return
        }
        if (forwardAuth.method == AccessMethod.HEADERS) {
            forwardAuth.firstInvalidHeader(type)?.let {
                throw IllegalArgumentException(context.getString(R.string.forward_auth_header_invalid, it.name))
            }
        }
        val profile = forwardAuth.profile(AccessOrigin.from(url), AccessOrigin.from(fallbackUrl), allowSelfSigned)
            ?: throw IllegalArgumentException(context.getString(R.string.forward_auth_connect_first))
        accessProfileStore.setDraft(
            draftId,
            profile.copy(usesNativeLogin = profile.usesNativeLogin || !ForwardAuth.canSkipNativeLogin(type)),
            urls
        )
    }

    suspend fun testHeaders(url: String, allowSelfSigned: Boolean): ProxyOutcome {
        val origin = AccessOrigin.from(url) ?: return ProxyOutcome.BLOCKED
        return forwardAuthProber.probe(
            url, OriginAccess(origin, headers = forwardAuth.headers), forwardAuth.allowsInsecure, allowSelfSigned
        )
    }

    fun signOutOfProxy() {
        forwardAuth.signOut()
        viewModelScope.launch {
            if (_existingInstance.value != null) servicesRepository.signOutOfProxy(draftId)
            else servicesRepository.removeWebDataUnlessUsed(draftId, lastUrls)
        }
    }

    private suspend fun promptForCode(
        challenge: TwoFactorChallenge,
        resend: (suspend (TwoFactorChallenge) -> TwoFactorChallenge)? = null,
        verify: suspend (TwoFactorChallenge, String) -> String
    ): String {
        val prompt = TwoFactorPrompt(challenge, resend, verify)
        _twoFactorPrompt.value = prompt
        try {
            return prompt.result.await()
        } finally {
            _twoFactorPrompt.value = null
        }
    }

    private suspend fun promptForBeszelCode(challenge: TwoFactorChallenge, allowSelfSigned: Boolean): String =
        promptForCode(
            beszelRepository.requestTwoFactorCode(challenge, allowSelfSigned),
            resend = { beszelRepository.requestTwoFactorCode(it, allowSelfSigned) },
            verify = { current, code -> beszelRepository.completeTwoFactor(current, code, allowSelfSigned) }
        )

    /** Runs a login and saves what it returns. A closed prompt or sign-in page is not an error. */
    private fun save(url: String, allowSelfSigned: Boolean, build: suspend () -> ServiceInstance) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val instance = build()
                accessProfileStore.commitDraft(draftId, instance)
                servicesRepository.saveInstance(instance)
                _existingInstance.value = instance
                _saved.value = true
            } catch (cancelled: CancellationException) {
                ensureActive()
            } catch (error: Exception) {
                report(error, url, allowSelfSigned)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun report(error: Exception, url: String, allowSelfSigned: Boolean) {
        _error.value = ErrorHandler.getMessage(context, error)
        if (!forwardAuth.isOn) {
            _offersForwardAuth.value = forwardAuthProber.isProtected(url, allowSelfSigned)
        }
    }

    // MARK: - OAuth

    /** Asks the server which providers it has; a single one is used right away. */
    fun loadOAuthProviders(label: String, url: String, fallbackUrl: String, email: String, allowSelfSigned: Boolean) {
        val cleanUrl = cleanOptionalUrl(url) ?: return
        val cleanFallback = cleanOptionalUrl(fallbackUrl)
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            var single: BeszelAuthMethods.Provider? = null
            try {
                applyDraft(ServiceType.BESZEL, cleanUrl, cleanFallback, allowSelfSigned)
                val (base, methods) = beszelRepository.authMethods(cleanUrl, cleanFallback, allowSelfSigned)
                oauthBaseUrl = base
                _oauthMethods.value = methods
                when (methods.providers.size) {
                    0 -> throw OAuthException(R.string.oauth_unavailable)
                    1 -> single = methods.providers.first()
                    else -> _choosesProvider.value = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                report(error, cleanUrl, allowSelfSigned)
            } finally {
                _isLoading.value = false
            }
            single?.let { signInWithOAuth(it, label, url, fallbackUrl, email, allowSelfSigned) }
        }
    }

    fun cancelProviderChoice() {
        _choosesProvider.value = false
    }

    fun signInWithOAuth(
        provider: BeszelAuthMethods.Provider,
        label: String, url: String, fallbackUrl: String, email: String, allowSelfSigned: Boolean
    ) {
        _choosesProvider.value = false
        val cleanUrl = cleanOptionalUrl(url) ?: return
        val cleanFallback = cleanOptionalUrl(fallbackUrl)
        save(cleanUrl, allowSelfSigned) {
            // The address may have changed since the providers were loaded.
            if (oauthBaseUrl !in listOf(cleanUrl, cleanFallback)) throw OAuthException(R.string.oauth_invalid_reply)
            applyDraft(ServiceType.BESZEL, cleanUrl, cleanFallback, allowSelfSigned)
            val page = provider.signInUrl()
            val reply = OAuthReplies.expect()
            _oauthPage.value = page
            val code = try {
                OAuthCallback.code(reply.await(), provider.state)
            } finally {
                _oauthPage.value = null
            }
            val typed = email.trim().ifBlank { null }
            var account = typed
            val token = try {
                val session = beszelRepository.completeOAuth(provider, code, oauthBaseUrl, typed, allowSelfSigned)
                account = session.record?.email?.ifBlank { null } ?: typed
                session.token
            } catch (required: TwoFactorRequiredException) {
                promptForBeszelCode(required.challenge, allowSelfSigned)
            }
            ServiceInstance(
                id = draftId,
                type = ServiceType.BESZEL,
                label = label.trim().ifBlank { ServiceType.BESZEL.displayName },
                url = cleanUrl,
                token = token,
                username = account,
                fallbackUrl = cleanFallback,
                allowSelfSigned = allowSelfSigned
            )
        }
    }

    /** The user came back without finishing. */
    fun cancelOAuth() = OAuthReplies.cancel()

    /** Checks that the service's API really answers with the proxy credentials alone. */
    private suspend fun buildWithoutLogin(
        type: ServiceType, label: String, url: String, fallbackUrl: String?, allowSelfSigned: Boolean
    ): ServiceInstance {
        try {
            when (type) {
                ServiceType.ADGUARD_HOME -> adGuardHomeRepository.authenticate(url, "", "", allowSelfSigned = allowSelfSigned)
                ServiceType.QBITTORRENT -> mediaArrRepository.checkQbittorrentWithoutLogin(url, allowSelfSigned)
                ServiceType.MALTRAIL -> maltrailRepository.authenticate(
                    url = url, username = null, password = null, fallbackUrl = fallbackUrl, allowSelfSigned = allowSelfSigned
                )
                ServiceType.UPTIME_KUMA -> uptimeKumaRepository.authenticate(
                    url = url, username = null, passwordOrApiKey = null, fallbackUrl = fallbackUrl, allowSelfSigned = allowSelfSigned
                )
                ServiceType.GLUETUN, ServiceType.FLARESOLVERR -> mediaArrRepository.authenticateWithApiKey(
                    url = url, serviceType = type, apiKey = "", fallbackUrl = fallbackUrl, allowSelfSigned = allowSelfSigned
                )
                ServiceType.SCRUTINY -> scrutinyRepository.validate(url, fallbackUrl, allowSelfSigned)
                ServiceType.DOCKGE -> dockgeRepository.validate(url, fallbackUrl, "", allowSelfSigned)
                else -> throw IllegalArgumentException(context.getString(R.string.error_unknown))
            }
        } catch (_: DockgeLoginRequiredException) {
            throw IllegalArgumentException(context.getString(R.string.forward_auth_needs_login))
        } catch (error: retrofit2.HttpException) {
            if (error.code() in setOf(401, 403)) {
                throw IllegalArgumentException(context.getString(R.string.forward_auth_needs_login))
            }
            throw error
        } catch (error: IllegalStateException) {
            if (Regex("\\b(401|403)\\b").containsMatchIn(error.message.orEmpty())) {
                throw IllegalArgumentException(context.getString(R.string.forward_auth_needs_login))
            }
            throw error
        }
        return ServiceInstance(
            id = draftId, type = type, label = label, url = url, fallbackUrl = fallbackUrl, allowSelfSigned = allowSelfSigned
        )
    }

    fun saveInstance(
        serviceType: ServiceType,
        label: String,
        url: String,
        username: String = "",
        password: String = "",
        apiKey: String = "",
        fallbackUrl: String = "",
        mfaCode: String = "",
        allowSelfSigned: Boolean = true,
        proxmoxRealm: String = "pam",
        proxmoxOtp: String = "",
        proxmoxUseApiToken: Boolean = false
    ) {
        val requestedUrl = cleanOptionalUrl(url)
        if (requestedUrl == null) {
            _error.value = context.getString(R.string.login_error_url_required)
            return
        }
        save(requestedUrl, allowSelfSigned) {
            val existing = _existingInstance.value
            val instanceId = draftId
            val normalizedLabel = label.trim().ifBlank { serviceType.displayName }
            val cleanUrl = cleanUrl(url)
            val cleanFallbackUrl = cleanOptionalUrl(fallbackUrl)
            val trimmedUsername = username.trim()
            val trimmedPassword = password.trim()
            val trimmedApiKey = apiKey.trim()
            val trimmedMfaCode = mfaCode.trim()
            val normalizedStoredUsername = when (serviceType) {
                ServiceType.PROXMOX -> {
                    if (trimmedUsername.isBlank()) ""
                    else if (trimmedUsername.contains("@")) trimmedUsername
                    else "${trimmedUsername}@${proxmoxRealm.ifBlank { "pam" }}"
                }
                else -> trimmedUsername
            }

            applyDraft(serviceType, cleanUrl, cleanFallbackUrl, allowSelfSigned)
            if (skipsNativeLogin(serviceType)) {
                buildWithoutLogin(serviceType, normalizedLabel, cleanUrl, cleanFallbackUrl, allowSelfSigned)
            } else {
                // Going back to the service's own login has to be tested like a new one.
                val hadNoLogin = existing != null && accessProfileStore.profile(existing.id)?.usesNativeLogin == false
                val metadataOnly = existing != null &&
                    !hadNoLogin &&
                    existing.url == cleanUrl &&
                    existing.username.orEmpty() == normalizedStoredUsername &&
                    existing.apiKey.orEmpty() == trimmedApiKey &&
                    existing.piHoleStoredSecret.orEmpty() == trimmedPassword &&
                    existing.allowSelfSigned == allowSelfSigned

                if (metadataOnly) {
                    existing.copy(
                        label = normalizedLabel,
                        fallbackUrl = cleanFallbackUrl,
                        allowSelfSigned = allowSelfSigned
                    )
                } else {
                    when (serviceType) {
                        ServiceType.PORTAINER -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            portainerRepository.authenticateWithApiKey(cleanUrl, trimmedApiKey, allowSelfSigned = allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = existing?.token.orEmpty(),
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.PIHOLE -> {
                            val secret = trimmedPassword.ifBlank {
                                existing?.piHoleStoredSecret ?: throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                            }
                            val token = piholeRepository.authenticate(cleanUrl, secret, allowSelfSigned = allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = token,
                                piholePassword = secret,
                                piholeAuthMode = if (token == secret) PiHoleAuthMode.LEGACY else PiHoleAuthMode.SESSION,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.ADGUARD_HOME -> {
                            require(trimmedUsername.isNotBlank()) { context.getString(R.string.login_error_username_required) }
                            val authPassword = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.username == trimmedUsername) {
                                    return@ifBlank existing.password.orEmpty()
                                }
                                throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                            }
                            require(authPassword.isNotBlank()) { context.getString(R.string.login_error_password_required) }
                            adGuardHomeRepository.authenticate(cleanUrl, trimmedUsername, authPassword, allowSelfSigned = allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                username = trimmedUsername,
                                password = authPassword,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.BESZEL -> {
                            require(trimmedUsername.isNotBlank()) { context.getString(R.string.login_error_email_required) }
                            val authPassword = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.username == trimmedUsername) {
                                    return@ifBlank ""
                                }
                                throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                            }
                            require(authPassword.isNotBlank()) { context.getString(R.string.login_error_password_required) }
                            val token = try {
                                beszelRepository.authenticate(
                                    url = cleanUrl,
                                    email = trimmedUsername,
                                    password = authPassword,
                                    fallbackUrl = cleanFallbackUrl,
                                    allowSelfSigned = allowSelfSigned
                                )
                            } catch (required: TwoFactorRequiredException) {
                                promptForBeszelCode(required.challenge, allowSelfSigned)
                            }
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = token,
                                username = trimmedUsername,
                                fallbackUrl = cleanFallbackUrl,
                                password = authPassword
                            )
                        }
                        ServiceType.GITEA -> {
                            require(trimmedUsername.isNotBlank()) { context.getString(R.string.login_error_username_required) }
                            val authPassword = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.username == trimmedUsername) {
                                    return@ifBlank ""
                                }
                                throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                            }
                            require(authPassword.isNotBlank()) { context.getString(R.string.login_error_password_required) }
                            val token = giteaRepository.authenticate(cleanUrl, trimmedUsername, authPassword, allowSelfSigned = allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = token,
                                username = trimmedUsername,
                                fallbackUrl = cleanFallbackUrl,
                                password = authPassword
                            )
                        }
                        ServiceType.NGINX_PROXY_MANAGER -> {
                            require(trimmedUsername.isNotBlank()) { context.getString(R.string.login_error_email_required) }
                            val authPassword = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.username == trimmedUsername) {
                                    return@ifBlank ""
                                }
                                throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                            }
                            require(authPassword.isNotBlank()) { context.getString(R.string.login_error_password_required) }
                            val token = try {
                                nginxProxyManagerRepository.authenticate(cleanUrl, trimmedUsername, authPassword, allowSelfSigned = allowSelfSigned)
                            } catch (required: TwoFactorRequiredException) {
                                promptForCode(
                                    required.challenge,
                                    verify = { current, code ->
                                        nginxProxyManagerRepository.completeTwoFactor(current, code, allowSelfSigned)
                                    }
                                )
                            }
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = token,
                                username = trimmedUsername,
                                fallbackUrl = cleanFallbackUrl,
                                password = authPassword
                            )
                        }
                        ServiceType.HEALTHCHECKS -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            healthchecksRepository.validateApiKey(cleanUrl, trimmedApiKey, allowSelfSigned = allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.PANGOLIN -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            val orgId = trimmedUsername.ifBlank { null }
                            pangolinRepository.authenticate(cleanUrl, trimmedApiKey, orgId, allowSelfSigned = allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                username = orgId,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.LINUX_UPDATE -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            linuxUpdateRepository.authenticate(cleanUrl, trimmedApiKey, allowSelfSigned = allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.TECHNITIUM -> {
                            require(trimmedUsername.isNotBlank()) { context.getString(R.string.login_error_username_required) }
                            val authPassword = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.username == trimmedUsername) {
                                    return@ifBlank existing.password.orEmpty()
                                }
                                throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                            }
                            require(authPassword.isNotBlank()) { context.getString(R.string.login_error_password_required) }

                            val sessionToken = technitiumRepository.authenticate(
                                url = cleanUrl,
                                username = trimmedUsername,
                                password = authPassword,
                                totp = trimmedMfaCode,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )

                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = sessionToken,
                                username = trimmedUsername,
                                password = authPassword,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.DOCKHAND -> {
                            val authPassword = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.username == trimmedUsername) {
                                    return@ifBlank existing.password.orEmpty()
                                }
                                throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                            }
                            require(trimmedUsername.isNotBlank()) { context.getString(R.string.login_error_username_required) }
                            require(authPassword.isNotBlank()) { context.getString(R.string.login_error_password_required) }

                            val cookieHeader = dockhandRepository.authenticate(
                                url = cleanUrl,
                                username = trimmedUsername,
                                password = authPassword,
                                mfaCode = trimmedMfaCode,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )

                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = cookieHeader,
                                username = trimmedUsername,
                                password = authPassword,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.DOCKMON -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            dockmonRepository.authenticate(
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.KOMODO -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            val apiSecret = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.apiKey == trimmedApiKey) {
                                    return@ifBlank existing.password.orEmpty()
                                }
                                throw IllegalArgumentException(context.getString(R.string.komodo_login_error_api_secret_required))
                            }
                            require(apiSecret.isNotBlank()) { context.getString(R.string.komodo_login_error_api_secret_required) }

                            komodoRepository.authenticate(
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                apiSecret = apiSecret,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                password = apiSecret,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.MALTRAIL -> {
                            if (trimmedUsername.isBlank() != trimmedPassword.isBlank()) {
                                throw IllegalArgumentException(
                                    context.getString(
                                        if (trimmedUsername.isBlank()) {
                                            R.string.login_error_username_required
                                        } else {
                                            R.string.login_error_password_required
                                        }
                                    )
                                )
                            }
                            val resolvedUsername = trimmedUsername.ifBlank { existing?.username.orEmpty() }
                            val resolvedPassword = if (trimmedUsername.isBlank() && trimmedPassword.isBlank()) {
                                ""
                            } else {
                                trimmedPassword.ifBlank { existing?.password.orEmpty() }
                            }
                            val sessionCookie = maltrailRepository.authenticate(
                                url = cleanUrl,
                                username = resolvedUsername.ifBlank { null },
                                password = resolvedPassword.ifBlank { null },
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = sessionCookie,
                                username = resolvedUsername.ifBlank { null },
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned,
                                password = resolvedPassword.ifBlank { null }
                            )
                        }
                        ServiceType.UPTIME_KUMA -> {
                            val resolvedPassword = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.username.orEmpty() == trimmedUsername) {
                                    return@ifBlank existing.password.orEmpty()
                                }
                                ""
                            }
                            uptimeKumaRepository.authenticate(
                                url = cleanUrl,
                                username = trimmedUsername.ifBlank { null },
                                passwordOrApiKey = resolvedPassword.ifBlank { null },
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                username = trimmedUsername.ifBlank { null },
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned,
                                password = resolvedPassword.ifBlank { null }
                            )
                        }
                        ServiceType.UNIFI_NETWORK -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            unifiRepository.authenticate(
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = canonicalUnifiUrl(cleanUrl),
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                        }
                        ServiceType.CRAFTY_CONTROLLER -> {
                            require(trimmedUsername.isNotBlank()) { context.getString(R.string.login_error_username_required) }
                            val authPassword = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.username == trimmedUsername) {
                                    return@ifBlank existing.password.orEmpty()
                                }
                                throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                            }
                            require(authPassword.isNotBlank()) { context.getString(R.string.login_error_password_required) }

                            val token = craftyRepository.authenticate(
                                url = cleanUrl,
                                username = trimmedUsername,
                                password = authPassword,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = token,
                                username = trimmedUsername,
                                password = authPassword,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.JELLYSTAT -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            jellystatRepository.authenticate(cleanUrl, trimmedApiKey, allowSelfSigned = allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.PATCHMON -> {
                            require(trimmedUsername.isNotBlank()) { context.getString(R.string.patchmon_login_error_token_key_required) }
                            val tokenSecret = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.username == trimmedUsername) {
                                    return@ifBlank existing.password.orEmpty()
                                }
                                throw IllegalArgumentException(context.getString(R.string.patchmon_login_error_token_secret_required))
                            }
                            require(tokenSecret.isNotBlank()) { context.getString(R.string.patchmon_login_error_token_secret_required) }
                            patchmonRepository.authenticate(cleanUrl, trimmedUsername, tokenSecret, allowSelfSigned = allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                username = trimmedUsername,
                                password = tokenSecret,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.PLEX -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            plexRepository.authenticate(cleanUrl, trimmedApiKey, allowSelfSigned = allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.WAKAPI -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            wakapiRepository.authenticate(
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.PROXMOX -> {
                            if (proxmoxUseApiToken) {
                                require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_api_key_label) }
                                proxmoxRepository.authenticateWithApiToken(
                                    url = cleanUrl,
                                    apiToken = trimmedApiKey,
                                    allowSelfSigned = allowSelfSigned
                                )
                                ServiceInstance(
                                    id = instanceId,
                                    type = serviceType,
                                    label = normalizedLabel,
                                    url = cleanUrl,
                                    apiKey = trimmedApiKey,
                                    fallbackUrl = cleanFallbackUrl
                                )
                            } else {
                                require(trimmedUsername.isNotBlank()) { context.getString(R.string.login_error_username_required) }
                                val resolvedPassword = trimmedPassword.ifBlank {
                                    if (existing != null && existing.url == cleanUrl && existing.username == normalizedStoredUsername) {
                                        return@ifBlank existing.password.orEmpty()
                                    }
                                    throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                                }
                                require(resolvedPassword.isNotBlank()) { context.getString(R.string.login_error_password_required) }
                                val authTicket = proxmoxRepository.authenticate(
                                    url = cleanUrl,
                                    username = trimmedUsername,
                                    password = resolvedPassword,
                                    otp = proxmoxOtp.ifBlank { null },
                                    realm = proxmoxRealm.ifBlank { "pam" },
                                    allowSelfSigned = allowSelfSigned
                                )
                                ServiceInstance(
                                    id = instanceId,
                                    type = serviceType,
                                    label = normalizedLabel,
                                    url = cleanUrl,
                                    token = authTicket.ticket,
                                    proxmoxCsrfToken = authTicket.csrfPreventionToken,
                                    username = authTicket.username,
                                    password = resolvedPassword,
                                    fallbackUrl = cleanFallbackUrl
                                )
                            }
                        }
                        ServiceType.TRUENAS -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            trueNasRepository.authenticate(
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                        }
                        ServiceType.QBITTORRENT -> {
                            require(trimmedUsername.isNotBlank()) { context.getString(R.string.login_error_username_required) }
                            val resolvedPassword = trimmedPassword.ifBlank {
                                if (existing != null && existing.url == cleanUrl && existing.username == trimmedUsername) {
                                    return@ifBlank existing.password.orEmpty()
                                }
                                throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                            }
                            require(resolvedPassword.isNotBlank()) { context.getString(R.string.login_error_password_required) }
                            val sid = mediaArrRepository.authenticateQbittorrent(
                                url = cleanUrl,
                                username = trimmedUsername,
                                password = resolvedPassword,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = sid,
                                username = trimmedUsername,
                                fallbackUrl = cleanFallbackUrl,
                                password = resolvedPassword
                            )
                        }
                        ServiceType.RADARR,
                        ServiceType.SONARR,
                        ServiceType.LIDARR,
                        ServiceType.JELLYSEERR,
                        ServiceType.PROWLARR,
                        ServiceType.BAZARR -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            mediaArrRepository.authenticateWithApiKey(
                                url = cleanUrl,
                                serviceType = serviceType,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.GLUETUN,
                        ServiceType.FLARESOLVERR -> {
                            mediaArrRepository.authenticateWithApiKey(
                                url = cleanUrl,
                                serviceType = serviceType,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey.ifBlank { null },
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.UNKNOWN -> throw IllegalArgumentException(context.getString(R.string.error_unknown))
                        ServiceType.PTERODACTYL -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            pterodactylRepository.authenticate(
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.SCRUTINY -> {
                            // Scrutiny has no login of its own.
                            scrutinyRepository.validate(cleanUrl, cleanFallbackUrl, allowSelfSigned)
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.DOCKGE -> {
                            val token = when {
                                trimmedPassword.isNotBlank() -> {
                                    require(trimmedUsername.isNotBlank()) { context.getString(R.string.login_error_username_required) }
                                    try {
                                        dockgeRepository.login(cleanUrl, cleanFallbackUrl, trimmedUsername, trimmedPassword, allowSelfSigned)
                                    } catch (required: TwoFactorRequiredException) {
                                        promptForCode(
                                            required.challenge,
                                            verify = { _, code ->
                                                dockgeRepository.login(
                                                    cleanUrl, cleanFallbackUrl, trimmedUsername, trimmedPassword, allowSelfSigned, code
                                                )
                                            }
                                        )
                                    }
                                }
                                existing != null && existing.token.isNotBlank() -> {
                                    require(existing.url == cleanUrl) { context.getString(R.string.login_error_password_required) }
                                    existing.token
                                }
                                else -> ""
                            }
                            try {
                                dockgeRepository.validate(cleanUrl, cleanFallbackUrl, token, allowSelfSigned)
                            } catch (_: DockgeLoginRequiredException) {
                                throw IllegalArgumentException(context.getString(R.string.login_error_password_required))
                            }
                            // The session does not expire, so the password is not kept.
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                token = token,
                                username = trimmedUsername.ifBlank { null },
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                        ServiceType.CALAGOPUS -> {
                            require(trimmedApiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
                            calagopusRepository.authenticate(
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl,
                                allowSelfSigned = allowSelfSigned
                            )
                            ServiceInstance(
                                id = instanceId,
                                type = serviceType,
                                label = normalizedLabel,
                                url = cleanUrl,
                                apiKey = trimmedApiKey,
                                fallbackUrl = cleanFallbackUrl
                            )
                        }
                    }
                }.copy(allowSelfSigned = allowSelfSigned)
            }
        }
    }

    private fun cleanUrl(url: String): String {
        var clean = url.trim()
        clean = clean.trimEnd { it == ')' || it == ']' || it == '}' || it == ',' || it == ';' }
        if (!clean.startsWith("http://") &&
            !clean.startsWith("https://") &&
            !clean.startsWith("ws://") &&
            !clean.startsWith("wss://")
        ) {
            clean = "https://$clean"
        }
        return clean.replace(Regex("/+$"), "")
    }

    private fun cleanOptionalUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return null
        return cleanUrl(trimmed)
    }

    private fun canonicalUnifiUrl(url: String): String {
        val lowered = url.lowercase()
        return if (lowered.contains("unifi.ui.com") || lowered.contains("api.ui.com")) {
            "https://api.ui.com"
        } else {
            stripKnownUnifiApiPath(url)
        }
    }

    private fun stripKnownUnifiApiPath(raw: String): String {
        return runCatching {
            val uri = URI(raw)
            val path = uri.rawPath.orEmpty()
            if (!isKnownUnifiApiPath(path)) return@runCatching raw
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, null, null, null).toString()
        }.getOrDefault(raw)
    }

    private fun isKnownUnifiApiPath(path: String): Boolean {
        val normalized = path.trimEnd('/')
        return normalized == "/proxy/network/integration/v1" ||
            normalized.startsWith("/proxy/network/integration/v1/") ||
            normalized == "/v1" ||
            normalized.startsWith("/v1/")
    }

    fun clearError() {
        _error.value = null
    }
}
