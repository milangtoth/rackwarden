package com.homelab.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.data.remote.access.AccessInterceptor
import com.homelab.app.data.remote.access.AccessProfileStore
import com.homelab.app.util.GlobalEventBus
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MiaApp : Application(), SingletonImageLoader.Factory {
    @Inject
    lateinit var clients: TlsClientSelector

    @Inject
    lateinit var accessProfileStore: AccessProfileStore

    @Inject
    lateinit var globalEventBus: GlobalEventBus

    /** Artwork of services behind a proxy needs its credentials too. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = clients.forAllowSelfSigned(false).newBuilder().apply {
            interceptors().clear()
            addInterceptor(AccessInterceptor(accessProfileStore, globalEventBus, allowSelfSigned = false))
        }.build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}
