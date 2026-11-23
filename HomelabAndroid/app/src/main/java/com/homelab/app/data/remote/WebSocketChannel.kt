package com.homelab.app.data.remote

import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One WebSocket, so tests can stand in for the server. */
interface WebSocketChannel {
    /** Throws when the connection is gone. */
    fun send(text: String)

    /** The next message; throws when the connection is gone. */
    suspend fun receive(): String
    fun close()
}

fun interface WebSocketConnector {
    fun open(url: String, allowSelfSigned: Boolean): WebSocketChannel
}

class OkHttpWebSocketConnector(private val clients: TlsClientSelector) : WebSocketConnector {
    override fun open(url: String, allowSelfSigned: Boolean): WebSocketChannel {
        val client = clients.forAllowSelfSigned(allowSelfSigned).newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // A redirect during the handshake must not carry credentials elsewhere.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        return OkHttpChannel(client, url)
    }
}

private class OkHttpChannel(client: OkHttpClient, url: String) : WebSocketChannel {
    private val messages = Channel<String>(Channel.UNLIMITED)

    @Volatile
    private var failure: IOException? = null

    private val socket = client.newWebSocket(
        // OkHttp accepts ws and wss here as well.
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.trySend(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                messages.trySend(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end(t)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                end(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(null)
        }
    )

    private fun end(cause: Throwable?) {
        if (failure == null) failure = cause as? IOException ?: IOException("The connection was closed", cause)
        messages.close()
    }

    override fun send(text: String) {
        if (!socket.send(text)) throw failure ?: IOException("The connection was closed")
    }

    override suspend fun receive(): String =
        messages.receiveCatching().getOrNull() ?: throw failure ?: IOException("The connection was closed")

    override fun close() {
        end(null)
        if (!socket.close(1000, null)) socket.cancel()
    }
}
