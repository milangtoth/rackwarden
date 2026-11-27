package com.homelab.app.data.remote.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Sessions the service rejected and that cannot be renewed without the user. */
@Singleton
class SignInTracker @Inject constructor() {
    private val _rejected = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Instance ID to the rejected token; a new token makes the entry obsolete. */
    val rejected: StateFlow<Map<String, String>> = _rejected

    private val attempts = ConcurrentHashMap<String, Long>()

    fun markRejected(instanceId: String, token: String) = _rejected.update { it + (instanceId to token) }

    fun isRejected(instanceId: String, token: String): Boolean = _rejected.value[instanceId] == token

    /** True at most once per interval, so renewals are not repeated with every request. */
    fun mayTry(key: String, intervalMs: Long = 600_000L, now: Long = System.currentTimeMillis()): Boolean {
        var allowed = false
        attempts.compute(key) { _, last ->
            allowed = last == null || now - last >= intervalMs
            if (allowed) now else last
        }
        return allowed
    }
}
