package com.homelab.app.data.remote

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/** The text packets of Socket.IO 4 (Engine.IO 4) that a plain client needs. */
sealed interface SocketIoPacket {
    data object Open : SocketIoPacket
    data object Ping : SocketIoPacket
    data object Connected : SocketIoPacket
    data class Refused(val reason: String) : SocketIoPacket
    data object Disconnected : SocketIoPacket
    data class Event(val name: String, val args: List<JsonElement>) : SocketIoPacket
    data class Ack(val id: Int, val args: List<JsonElement>) : SocketIoPacket
    data object Other : SocketIoPacket

    companion object {
        fun parse(text: String): SocketIoPacket {
            when (text.firstOrNull()) {
                '0' -> return Open
                '1' -> return Disconnected
                '2' -> return Ping
                '4' -> {}
                else -> return Other
            }
            val type = text.getOrNull(1) ?: return Other
            val body = text.drop(2)
            // Only the default namespace is used; others come as "/name,".
            if (body.startsWith("/")) return Other
            return when (type) {
                '0' -> Connected
                '1' -> Disconnected
                '4' -> Refused(
                    runCatching {
                        (Json.parseToJsonElement(body).jsonObject["message"] as? JsonPrimitive)?.contentOrNull
                    }.getOrNull().orEmpty()
                )
                '2', '3' -> {
                    val digits = body.takeWhile { it in '0'..'9' }
                    val array = runCatching { Json.parseToJsonElement(body.drop(digits.length)) as? JsonArray }.getOrNull()
                        ?: return Other
                    if (type == '3') return digits.toIntOrNull()?.let { Ack(it, array) } ?: Other
                    val name = (array.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Other
                    Event(name, array.drop(1))
                }
                else -> Other
            }
        }

        fun event(name: String, args: List<JsonElement>, id: Int): String =
            "42$id" + JsonArray(listOf<JsonElement>(JsonPrimitive(name)) + args)
    }
}

/** One Socket.IO connection, read by one caller at a time. */
class SocketIoConnection(private val channel: WebSocketChannel) {
    private var nextId = 0
    private val pending = ArrayDeque<SocketIoPacket.Event>()

    fun close() = channel.close()

    suspend fun connect() {
        while (true) {
            when (val packet = read()) {
                SocketIoPacket.Open -> channel.send("40")
                SocketIoPacket.Connected -> return
                is SocketIoPacket.Refused -> throw IOException(packet.reason)
                else -> {}
            }
        }
    }

    /** Sends an event and waits for its answer. Events arriving meanwhile are kept. */
    suspend fun call(name: String, args: List<JsonElement>, timeoutMs: Long): JsonElement {
        val id = nextId++
        channel.send(SocketIoPacket.event(name, args, id))
        return withTimeout(timeoutMs) {
            var answer: JsonElement? = null
            while (answer == null) {
                when (val packet = read()) {
                    is SocketIoPacket.Ack -> if (packet.id == id) answer = packet.args.firstOrNull() ?: JsonNull
                    is SocketIoPacket.Event -> pending.addLast(packet)
                    else -> {}
                }
            }
            answer
        }
    }

    suspend fun nextEvent(): SocketIoPacket.Event {
        pending.removeFirstOrNull()?.let { return it }
        while (true) {
            (read() as? SocketIoPacket.Event)?.let { return it }
        }
    }

    /** Returns events to the front, for whoever reads next. */
    fun putBack(events: List<SocketIoPacket.Event>) {
        events.asReversed().forEach { pending.addFirst(it) }
    }

    private suspend fun read(): SocketIoPacket {
        while (true) {
            when (val packet = SocketIoPacket.parse(channel.receive())) {
                SocketIoPacket.Ping -> channel.send("3")
                SocketIoPacket.Disconnected -> throw IOException("The connection was closed")
                SocketIoPacket.Other -> {}
                else -> return packet
            }
        }
    }
}
