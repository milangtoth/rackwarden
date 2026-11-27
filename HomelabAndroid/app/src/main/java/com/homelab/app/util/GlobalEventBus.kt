package com.homelab.app.util

import com.homelab.app.domain.model.OriginAccess
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A proxy rejected these credentials of an instance. */
data class ProxyExpired(val instanceId: String, val access: OriginAccess)

@Singleton
class GlobalEventBus @Inject constructor() {
    private val _authErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val authErrors = _authErrors.asSharedFlow()

    private val _proxyExpired = MutableSharedFlow<ProxyExpired>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val proxyExpired = _proxyExpired.asSharedFlow()

    fun emitProxyExpired(event: ProxyExpired) {
        _proxyExpired.tryEmit(event)
    }

    fun emitAuthError(instanceId: String) {
        _authErrors.tryEmit(instanceId)
    }
}
