package com.homelab.app.ui.forwardauth

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.homelab.app.data.repository.LocalPreferencesRepository
import com.homelab.app.domain.model.AccessCookie
import com.homelab.app.domain.model.AccessOrigin
import com.homelab.app.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Browser data of proxy sign-ins. Shared by all services, so one sign-in covers them,
 * or kept apart per instance when the user turns sharing off.
 *
 * Older WebViews have a single store for the whole app, so they can only share.
 */
@Singleton
class ForwardAuthWebData @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: LocalPreferencesRepository
) {
    val canSeparate: Boolean
        get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    private suspend fun profileName(instanceId: String): String =
        if (preferences.sharesForwardAuth.first()) SHARED else PREFIX + instanceId

    /** Must run on the main thread, before the web view is used in any other way. */
    suspend fun attach(webView: WebView, instanceId: String): CookieManager {
        if (!canSeparate) return CookieManager.getInstance()
        val name = profileName(instanceId)
        val profile = ProfileStore.getInstance().getOrCreateProfile(name)
        WebViewCompat.setProfile(webView, name)
        return profile.cookieManager
    }

    /** Forgets one service. A shared sign-in at the identity provider stays, as other services use it. */
    suspend fun remove(instanceId: String, urls: List<String?>) = onMain {
        if (!canSeparate) {
            clearHosts(CookieManager.getInstance(), urls)
            return@onMain
        }
        deleteProfile(PREFIX + instanceId)
        if (SHARED in names()) clearHosts(ProfileStore.getInstance().getOrCreateProfile(SHARED).cookieManager, urls)
    }

    /** Drops data the current sharing setting no longer uses, or of services that are gone. */
    suspend fun removeOrphans(known: Set<String>) = onMain {
        if (!canSeparate) return@onMain
        val kept = if (preferences.sharesForwardAuth.first()) setOf(SHARED) else known.map { PREFIX + it }.toSet()
        names().filter { (it == SHARED || it.startsWith(PREFIX)) && it !in kept }.forEach { deleteProfile(it) }
    }

    suspend fun removeAll() = onMain {
        if (!canSeparate) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        } else {
            names().filter { it == SHARED || it.startsWith(PREFIX) }.forEach { deleteProfile(it) }
        }
    }

    private fun names(): List<String> = ProfileStore.getInstance().allProfileNames

    private fun deleteProfile(name: String) {
        if (name !in names()) return
        // A profile used since the app started cannot be deleted, so its data goes first.
        val profile = ProfileStore.getInstance().getOrCreateProfile(name)
        profile.cookieManager.removeAllCookies(null)
        profile.cookieManager.flush()
        profile.webStorage.deleteAllData()
        runCatching { ProfileStore.getInstance().deleteProfile(name) }
    }

    /** Only cookies of these hosts themselves, not of a domain they share with others. */
    private fun clearHosts(cookies: CookieManager, urls: List<String?>) {
        urls.filterNotNull().filter { AccessOrigin.from(it) != null }.forEach { url ->
            parse(cookies.getCookie(url)).forEach { cookies.setCookie(url, "${it.name}=; Max-Age=0; Path=/") }
        }
        cookies.flush()
    }

    private suspend fun onMain(work: suspend () -> Unit) = withContext(Dispatchers.Main) {
        try {
            work()
        } catch (error: Exception) {
            // Without a working WebView there is no data to remove.
            Logger.w("ForwardAuth", "Web data unavailable: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        private const val SHARED = "forward-auth-shared"
        private const val PREFIX = "forward-auth-"

        /** The cookies a browser would send, as [CookieManager] reports them. */
        fun parse(header: String?): List<AccessCookie> = header.orEmpty().split(';')
            .map { it.trim() }
            .filter { it.contains('=') && !it.startsWith("=") }
            .map { AccessCookie(it.substringBefore('='), it.substringAfter('=')) }
    }
}
