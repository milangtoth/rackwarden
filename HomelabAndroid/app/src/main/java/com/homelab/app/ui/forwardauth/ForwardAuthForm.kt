package com.homelab.app.ui.forwardauth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.homelab.app.data.remote.access.ForwardAuth
import com.homelab.app.domain.model.AccessHeader
import com.homelab.app.domain.model.AccessMethod
import com.homelab.app.domain.model.AccessOrigin
import com.homelab.app.domain.model.AccessProfile
import com.homelab.app.domain.model.OriginAccess
import com.homelab.app.util.ServiceType

/** What the user entered about the proxy, until the service is saved. */
class ForwardAuthForm {
    data class Row(val name: String = "", val value: String = "")

    var isOn by mutableStateOf(false)
    var method by mutableStateOf(AccessMethod.BROWSER)
    val rows = mutableStateListOf(Row())
    var allowsInsecure by mutableStateOf(false)
    var skipsNativeLogin by mutableStateOf(false)
    var notice by mutableStateOf<Int?>(null)
    private val access = mutableStateMapOf<AccessOrigin, OriginAccess>()

    val headers: List<AccessHeader>
        get() = rows.map { AccessHeader(it.name.trim(), it.value.trim()) }
            .filter { it.name.isNotEmpty() || it.value.isNotEmpty() }

    val hasCredentials: Boolean get() = access.isNotEmpty() || headers.isNotEmpty()

    fun load(profile: AccessProfile?) {
        if (profile == null || !profile.isEnabled) return
        isOn = true
        method = profile.method
        allowsInsecure = profile.allowsInsecureTransport
        skipsNativeLogin = !profile.usesNativeLogin
        profile.origins.forEach { access[it.origin] = it }
        profile.origins.firstOrNull { it.headers.isNotEmpty() }?.let { saved ->
            rows.clear()
            rows.addAll(saved.headers.map { Row(it.name, it.value) })
        }
    }

    fun connect(value: OriginAccess) {
        access[value.origin] = value
    }

    /** Forgets browser sessions only. */
    fun signOut() {
        access.filterValues { it.cookies.isNotEmpty() }.keys.forEach { access.remove(it) }
    }

    fun turnOff() {
        isOn = false
        skipsNativeLogin = false
        access.clear()
        rows.clear()
        rows.add(Row())
    }

    /** Edited headers need a new check before they count as connected. */
    fun isConnected(origin: AccessOrigin?): Boolean {
        val found = origin?.let { access[it] } ?: return false
        return when (method) {
            AccessMethod.BROWSER -> found.cookies.isNotEmpty()
            AccessMethod.HEADERS -> headers.isNotEmpty() && found.headers == headers
        }
    }

    fun needsConsent(origins: List<AccessOrigin>, allowSelfSigned: Boolean): Boolean =
        allowSelfSigned || origins.any { !it.isEncrypted }

    fun firstInvalidHeader(type: ServiceType): AccessHeader? =
        headers.firstOrNull { !ForwardAuth.canSend(it, type, usesNativeLogin = !skipsNativeLogin) }

    /** The profile to use, or null while the main address is not connected. */
    fun profile(primary: AccessOrigin?, fallback: AccessOrigin?, allowSelfSigned: Boolean): AccessProfile? {
        if (!isConnected(primary)) return null
        val origins = listOfNotNull(primary, fallback).distinct().filter { isConnected(it) }
        return AccessProfile(
            isEnabled = true,
            method = method,
            usesNativeLogin = !skipsNativeLogin,
            allowsInsecureTransport = allowsInsecure && needsConsent(origins, allowSelfSigned),
            origins = origins.mapNotNull { access[it] }.map {
                if (method == AccessMethod.BROWSER) OriginAccess(it.origin, cookies = it.cookies)
                else OriginAccess(it.origin, headers = it.headers)
            }
        )
    }
}
