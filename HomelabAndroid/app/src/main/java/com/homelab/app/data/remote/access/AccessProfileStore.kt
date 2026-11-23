package com.homelab.app.data.remote.access

import com.homelab.app.domain.model.AccessOrigin
import com.homelab.app.domain.model.AccessProfile
import com.homelab.app.domain.model.OriginAccess
import com.homelab.app.domain.model.ServiceInstance
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface AccessProfileStorage {
    fun load(): Map<String, AccessProfile>
    fun save(profiles: Map<String, AccessProfile>)
}

/**
 * Access profiles keyed by instance ID. The draft of the service being set up stays
 * in memory until it is saved, and is used for logins that have no instance yet.
 */
class AccessProfileStore(private val storage: AccessProfileStorage) {
    data class Match(val instanceId: String?, val profile: AccessProfile, val access: OriginAccess)
    private data class Draft(val id: String, val profile: AccessProfile?, val origins: Set<AccessOrigin>)

    private var saved: Map<String, AccessProfile>? = null
    private var draft: Draft? = null

    @Synchronized
    fun profile(id: String): AccessProfile? = current()[id]

    @Synchronized
    fun all(): Map<String, AccessProfile> = current()

    /** Credentials for a request, by its instance or, for logins, by the address it goes to. */
    @Synchronized
    fun match(instanceId: String?, origin: AccessOrigin?): Match? {
        if (origin == null) return null
        if (instanceId != null) return matchIn(instanceId, current()[instanceId], origin)
        draft?.let { active ->
            // Turning forward auth off in the editor must also apply to its test login.
            if (origin in active.origins) return matchIn(null, active.profile, origin)
        }
        return current().entries.firstNotNullOfOrNull { matchIn(it.key, it.value, origin) }
    }

    private fun matchIn(id: String?, profile: AccessProfile?, origin: AccessOrigin): Match? {
        val access = profile?.access(origin) ?: return null
        return Match(id, profile, access)
    }

    /** Removes the saved session cookies; headers and settings stay. */
    @Synchronized
    fun signOut(id: String) = store(current()[id]?.withoutCookies, id)

    /** Replaces the credentials of one origin. Returns false if it is no longer in use. */
    @Synchronized
    fun update(access: OriginAccess, id: String): Boolean {
        val profile = current()[id]?.takeIf { it.isEnabled } ?: return false
        store(profile.copy(origins = profile.origins.filter { it.origin != access.origin } + access), id)
        return true
    }

    @Synchronized
    fun set(profile: AccessProfile?, id: String) = store(profile, id)

    @Synchronized
    fun setDraft(id: String, profile: AccessProfile?, urls: List<String?>) {
        draft = Draft(id, profile, urls.mapNotNull { AccessOrigin.from(it) }.toSet())
    }

    @Synchronized
    fun discardDraft(id: String) {
        if (draft?.id == id) draft = null
    }

    @Synchronized
    fun commitDraft(id: String, instance: ServiceInstance) {
        val active = draft?.takeIf { it.id == id } ?: return
        draft = null
        store(active.profile?.limitedTo(listOf(instance.url, instance.fallbackUrl)), instance.id)
    }

    private fun current(): Map<String, AccessProfile> =
        saved ?: storage.load().also { saved = it }

    private fun store(profile: AccessProfile?, id: String) {
        val all = current()
        if (all[id] == profile) return
        val next = if (profile == null) all - id else all + (id to profile)
        saved = next
        storage.save(next)
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): Map<String, AccessProfile> = json.decodeFromString(text)
        fun encode(profiles: Map<String, AccessProfile>): String = json.encodeToString(profiles)
    }
}
