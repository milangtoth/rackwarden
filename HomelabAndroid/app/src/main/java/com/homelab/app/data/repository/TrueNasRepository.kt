package com.homelab.app.data.repository

import android.content.Context
import com.homelab.app.R
import com.homelab.app.util.ServiceType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import com.homelab.app.data.remote.WebSocketChannel
import com.homelab.app.data.remote.WebSocketConnector
import com.homelab.app.data.remote.access.ForwardAuthInsecureException
import com.homelab.app.data.remote.access.ForwardAuthRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

data class TrueNasSystemInfo(
    val hostname: String?,
    val version: String?,
    val product: String?,
    val uptimeSeconds: Long?,
    val physicalMemoryBytes: Double?
)

data class TrueNasPool(
    val id: String,
    val name: String,
    val status: String,
    val usedBytes: Double,
    val totalBytes: Double,
    val freeBytes: Double,
    val healthy: Boolean
) {
    val usedFraction: Float
        get() = if (totalBytes > 0.0) (usedBytes / totalBytes).coerceIn(0.0, 1.0).toFloat() else 0f
}

data class TrueNasDisk(
    val id: String,
    val name: String,
    val model: String?,
    val serial: String?,
    val sizeBytes: Double?,
    val status: String?
)

data class TrueNasAlert(
    val id: String,
    val level: String,
    val title: String,
    val message: String,
    val timestamp: String?
)

data class TrueNasServiceStatus(
    val name: String,
    val state: String,
    val enabled: Boolean
) {
    val isRunning: Boolean
        get() = state.equals("RUNNING", ignoreCase = true) ||
            state.equals("running", ignoreCase = true) ||
            state.equals("started", ignoreCase = true) ||
            state.equals("active", ignoreCase = true)
}

data class TrueNasShareSummary(
    val smb: Int,
    val nfs: Int,
    val iscsi: Int
)

data class TrueNasWorkloadSummary(
    val apps: Int,
    val runningApps: Int,
    val virtualMachines: Int,
    val runningVirtualMachines: Int
)

/** An alert's HTML as plain text, with numbered readings (as from a UPS) as label and value. */
data class TrueNasAlertText(val text: String, val readings: List<Pair<String, String>>) {
    companion object {
        private val label = Regex("""\d+\) (.+)""")
        private val value = Regex("""[\w.\-]+: (.+)""")

        fun from(html: String): TrueNasAlertText {
            val lines = plain(html).split('\n').map { it.trim() }
            val body = mutableListOf<String>()
            val readings = mutableListOf<Pair<String, String>>()
            var index = 0
            while (index < lines.size) {
                // "1) Battery charge (percent)" followed by "battery.charge: 95"
                val name = label.matchEntire(lines[index])?.groupValues?.get(1)
                val reading = lines.getOrNull(index + 1)?.let { value.matchEntire(it)?.groupValues?.get(1) }
                if (name != null && reading != null) {
                    readings += reading(name, reading)
                    index += 2
                } else {
                    body += lines[index++]
                }
            }
            if (readings.isNotEmpty()) {
                body.removeAll { it.startsWith("UPS Statistics:") || it == "Statistics recovered:" }
            }
            return TrueNasAlertText(body.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim(), readings)
        }

        private fun reading(label: String, value: String): Pair<String, String> {
            fun without(unit: String) = label.replace(" ($unit)", "").trim()
            return when {
                "(percent)" in label && value.toDoubleOrNull() != null -> without("percent") to "$value%"
                "(seconds)" in label && value.toLongOrNull() != null ->
                    without("seconds") to formatSeconds(value.toLong())
                else -> label to value
            }
        }

        private fun formatSeconds(total: Long): String = listOf(
            total / 3600 to "h", total % 3600 / 60 to "min", total % 60 to "s"
        ).filter { it.first > 0 }.take(2).joinToString(" ") { "${it.first} ${it.second}" }.ifEmpty { "0 s" }

        private fun plain(html: String): String {
            var text = html.replace("\r", "")
                .replace(Regex("<br\\s*/?>|</(p|div|li|tr|h[1-6])>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")
                .replace(Regex("</?[a-zA-Z][^>]*>"), "")
            // "&amp;" last, so that "&amp;lt;" stays "&lt;".
            listOf(
                "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&amp;" to "&"
            ).forEach { (entity, character) -> text = text.replace(entity, character) }
            return text
        }
    }
}

data class TrueNasDashboardSnapshot(
    val system: TrueNasSystemInfo,
    val pools: List<TrueNasPool>,
    val disks: List<TrueNasDisk>,
    val alerts: List<TrueNasAlert>,
    val services: List<TrueNasServiceStatus>,
    val shares: TrueNasShareSummary,
    val workloads: TrueNasWorkloadSummary
) {
    val healthyPoolCount: Int get() = pools.count { it.healthy }
    val runningServiceCount: Int get() = services.count { it.isRunning }
    val totalStorageBytes: Double get() = pools.sumOf { it.totalBytes }
    val usedStorageBytes: Double get() = pools.sumOf { it.usedBytes }
}

@Singleton
class TrueNasRepository @Inject constructor(
    private val serviceInstancesRepository: ServiceInstancesRepository,
    private val connector: WebSocketConnector,
    private val json: Json,
    @param:ApplicationContext private val context: Context
) {

    suspend fun authenticate(
        url: String,
        apiKey: String,
        fallbackUrl: String? = null,
        allowSelfSigned: Boolean = false
    ) {
        require(apiKey.trim().isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
        val candidates = baseCandidates(url, fallbackUrl)
        require(candidates.isNotEmpty()) { context.getString(R.string.login_error_url_required) }
        val connection = connect(candidates, apiKey, allowSelfSigned)
        try {
            connection.call("system.info")
        } finally {
            connection.close()
        }
    }

    suspend fun getDashboard(instanceId: String): TrueNasDashboardSnapshot = coroutineScope {
        val instance = serviceInstancesRepository.getInstance(instanceId)
            ?: throw IllegalArgumentException("TrueNAS instance not found.")
        require(instance.type == ServiceType.TRUENAS) { "Instance is not TrueNAS." }
        val apiKey = instance.apiKey?.trim().orEmpty()
        require(apiKey.isNotBlank()) { context.getString(R.string.login_error_api_key_required) }
        val bases = baseCandidates(instance.url, instance.fallbackUrl)

        // TrueNAS allows 20 logins a minute per address, so one login serves the whole dashboard.
        var connection = connect(bases, apiKey, instance.allowSelfSigned)
        val replies = mutableMapOf<String, JsonElement>()
        val systemInfo: JsonElement
        try {
            systemInfo = connection.call("system.info")
            var reconnects = 0
            for ((method, params) in DASHBOARD_CALLS) {
                try {
                    replies[method] = connection.call(method, params)
                } catch (_: CallFailure) {
                    // The server refused this one; the connection stays usable.
                } catch (error: CancellationException) {
                    if (error !is TimeoutCancellationException) throw error
                    if (reconnects++ >= 2) throw IOException(error)
                    connection.close()
                    connection = connect(bases, apiKey, instance.allowSelfSigned)
                } catch (error: IOException) {
                    // The connection itself failed. Skip only this method.
                    if (reconnects++ >= 2) throw error
                    connection.close()
                    connection = connect(bases, apiKey, instance.allowSelfSigned)
                }
            }
        } finally {
            connection.close()
        }
        val empty: JsonElement = JsonArray(emptyList())
        fun reply(method: String): JsonElement = replies[method] ?: empty

        val system = parseSystemInfo(systemInfo)
        val pools = parsePools(reply("pool.query"))
        val disks = parseDisks(reply("disk.query"))
        val alerts = parseAlerts(reply("alert.list"))
        val services = parseServices(reply("service.query"))
        val apps = reply("app.query")
        val machines = reply("vm.query")
        val shares = TrueNasShareSummary(
            smb = countItems(reply("sharing.smb.query")),
            nfs = countItems(reply("sharing.nfs.query")),
            iscsi = countItems(reply("iscsi.target.query"))
        )
        val workloads = TrueNasWorkloadSummary(
            apps = countItems(apps),
            runningApps = countRunningItems(apps),
            virtualMachines = countItems(machines),
            runningVirtualMachines = countRunningItems(machines)
        )

        TrueNasDashboardSnapshot(
            system = system,
            pools = pools,
            disks = disks,
            alerts = alerts,
            services = services,
            shares = shares,
            workloads = workloads
        )
    }

    suspend fun getSummary(instanceId: String): TrueNasDashboardSnapshot = getDashboard(instanceId)

    /** The server answered, but refused the call. */
    private class CallFailure(message: String) : Exception(message)

    private val workingAttempts = ConcurrentHashMap<String, WebSocketAttempt>()

    private suspend fun connect(bases: List<String>, apiKey: String, allowSelfSigned: Boolean): Connection {
        var firstError: Exception? = null
        for (base in bases) {
            try {
                requireSecureTransport(base)
                return connect(base, apiKey, allowSelfSigned)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstError == null) firstError = error
            }
        }
        throw firstError ?: IllegalStateException(context.getString(R.string.truenas_request_failed))
    }

    private suspend fun connect(base: String, apiKey: String, allowSelfSigned: Boolean): Connection {
        val known = workingAttempts[base]
        var lastError: Exception? = null
        for (attempt in listOfNotNull(known) + ATTEMPTS.filter { it != known }) {
            val connection = Connection(connector.open(webSocketUrl(base, attempt.endpoint), allowSelfSigned), attempt.mode)
            try {
                if (attempt.mode == ProtocolMode.DDP) connection.sendDdpConnect()
                val accepted = connection.call("auth.login_with_api_key", listOf(JsonPrimitive(apiKey.trim())))
                if ((accepted as? JsonPrimitive)?.booleanOrNull == false) {
                    throw CallFailure(context.getString(R.string.truenas_invalid_api_key))
                }
                workingAttempts[base] = attempt
                return connection
            } catch (error: Exception) {
                connection.close()
                when {
                    error is CancellationException && error !is TimeoutCancellationException -> throw error
                    // A real answer is final: more attempts only use up the login limit.
                    error is CallFailure || error is ForwardAuthRequiredException || error is ForwardAuthInsecureException -> throw error
                    else -> lastError = if (error is TimeoutCancellationException) SocketTimeoutException() else error
                }
            }
        }
        throw lastError ?: IllegalStateException(context.getString(R.string.truenas_request_failed))
    }

    private fun parseSystemInfo(element: JsonElement): TrueNasSystemInfo {
        val obj = element.firstObjectOrNull()
        return TrueNasSystemInfo(
            hostname = obj?.string("hostname", "host_name", "name"),
            version = obj?.string("version", "version_short", "buildtime", "system_version"),
            product = obj?.string("system_product", "product", "model", "system_product_name", "system_manufacturer"),
            uptimeSeconds = obj?.long("uptime_seconds", "uptime", "uptime_s"),
            physicalMemoryBytes = obj?.double("physmem", "physical_memory", "memory")
        )
    }

    private fun parsePools(element: JsonElement): List<TrueNasPool> {
        return element.objectArray().mapIndexedNotNull { index, obj ->
            val name = obj.string("name", "pool_name", "id").ifBlank { return@mapIndexedNotNull null }
            val topology = obj["topology"] as? JsonObject
            val total = obj.optionalDouble("size", "total", "total_bytes", "raw_capacity")
                ?: topology?.optionalDouble("size", "total", "raw_capacity")
                ?: 0.0
            val free = obj.optionalDouble("free", "available", "avail", "free_bytes")
                ?: topology?.optionalDouble("free", "available", "avail")
                ?: 0.0
            val allocated = obj.optionalDouble("allocated", "used", "used_bytes")
                ?: topology?.optionalDouble("allocated", "used")
                ?: (total - free).coerceAtLeast(0.0)
            val status = obj.string("status", "health", "state").ifBlank { "UNKNOWN" }
            TrueNasPool(
                id = obj.string("id", "guid", "name").ifBlank { name },
                name = name,
                status = status,
                usedBytes = allocated,
                totalBytes = total,
                freeBytes = free,
                healthy = status.equals("ONLINE", ignoreCase = true) ||
                    status.equals("HEALTHY", ignoreCase = true) ||
                    obj.boolean("healthy", "is_healthy")
            )
        }
    }

    private fun parseDisks(element: JsonElement): List<TrueNasDisk> {
        return element.objectArray().mapIndexedNotNull { index, obj ->
            val name = obj.string("name", "devname", "identifier", "id").ifBlank { "disk-${index + 1}" }
            TrueNasDisk(
                id = obj.string("identifier", "id", "name").ifBlank { name },
                name = name,
                model = obj.string("model", "descr").ifBlank { null },
                serial = obj.string("serial", "serial_number").ifBlank { null },
                sizeBytes = obj.double("size", "capacity", "bytes").takeIf { it > 0.0 },
                status = obj.string("status", "state").ifBlank { null }
            )
        }
    }

    private fun parseAlerts(element: JsonElement): List<TrueNasAlert> {
        return element.objectArray().mapIndexed { index, obj ->
            val text = obj.string("formatted", "text", "message", "klass")
            TrueNasAlert(
                id = obj.string("uuid", "id").ifBlank { "alert-$index" },
                level = obj.string("level", "severity").ifBlank { "INFO" },
                title = obj.string("klass", "title").ifBlank { obj.string("level", "severity").ifBlank { "Alert" } },
                message = text.ifBlank { obj.toString() },
                timestamp = alertDate(obj["datetime"]) ?: obj.string("datetime", "timestamp", "created").ifBlank { null }
            )
        }
    }

    /** TrueNAS sends dates as {"$date": milliseconds}. */
    private fun alertDate(raw: JsonElement?): String? =
        ((raw as? JsonObject)?.get("\$date") as? JsonPrimitive)?.longOrNull?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        }

    private fun parseServices(element: JsonElement): List<TrueNasServiceStatus> {
        return element.objectArray().mapIndexedNotNull { index, obj ->
            val name = obj.string("service", "name", "id").ifBlank { return@mapIndexedNotNull null }
            TrueNasServiceStatus(
                name = name,
                state = obj.string("state", "status").ifBlank { "UNKNOWN" },
                enabled = obj.boolean("enable", "enabled")
            )
        }.sortedWith(
            compareByDescending<TrueNasServiceStatus> { it.isRunning }
                .thenByDescending { it.enabled }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun countItems(element: JsonElement): Int = element.collectionArray().size

    private fun countRunningItems(element: JsonElement): Int {
        return element.collectionArray().count { obj ->
            val state = obj.string("state", "status")
                .ifBlank { obj.stringAt("metadata", "state") }
                .ifBlank { obj.stringAt("status", "state") }
            state.equals("RUNNING", ignoreCase = true) ||
                state.equals("active", ignoreCase = true) ||
                state.contains("running", ignoreCase = true) ||
                obj.boolean("active", "running")
        }
    }

    private fun baseCandidates(url: String, fallbackUrl: String?): List<String> {
        return listOf(url, fallbackUrl.orEmpty())
            .mapNotNull { raw -> raw.trim().takeIf { it.isNotBlank() }?.let(::cleanUrl) }
            .distinct()
    }

    private fun requireSecureTransport(raw: String) {
        require(usesSecureApiTransport(raw)) { context.getString(R.string.truenas_secure_transport_required) }
    }

    private fun cleanUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("ws://", ignoreCase = true) ||
            trimmed.startsWith("wss://", ignoreCase = true)
        ) {
            return trimmed
        }
        return "https://$trimmed"
    }

    private fun webSocketUrl(rawBase: String, endpoint: String): String {
        val cleaned = cleanUrl(rawBase)
        val parseable = when {
            cleaned.startsWith("wss://", ignoreCase = true) -> "https://" + cleaned.substringAfter("://")
            cleaned.startsWith("ws://", ignoreCase = true) -> "http://" + cleaned.substringAfter("://")
            else -> cleaned
        }
        val httpUrl = parseable.toHttpUrl()
        val path = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
        val rebuilt = httpUrl.newBuilder()
            .encodedPath(path)
            .encodedQuery(null)
            .build()
            .toString()
        return when {
            rebuilt.startsWith("https://", ignoreCase = true) -> "wss://" + rebuilt.substringAfter("://")
            rebuilt.startsWith("http://", ignoreCase = true) -> "ws://" + rebuilt.substringAfter("://")
            else -> rebuilt
        }
    }

    private fun JsonElement.firstObjectOrNull(): JsonObject? {
        return when (this) {
            is JsonObject -> this
            is JsonArray -> this.firstOrNull() as? JsonObject
            else -> null
        }
    }

    private fun JsonElement.objectArray(): List<JsonObject> {
        return when (this) {
            is JsonArray -> mapNotNull { it as? JsonObject }
            is JsonObject -> {
                val direct = listOf("rows", "data", "items", "results", "result", "response")
                    .firstNotNullOfOrNull { key -> this[key] as? JsonArray }
                direct?.mapNotNull { it as? JsonObject } ?: listOf(this)
            }
            else -> emptyList()
        }
    }

    private fun JsonElement.collectionArray(): List<JsonObject> {
        return when (this) {
            is JsonArray -> mapNotNull { it as? JsonObject }
            is JsonObject -> {
                val direct = listOf("rows", "data", "items", "results", "result", "response")
                    .firstNotNullOfOrNull { key -> this[key] as? JsonArray }
                direct?.mapNotNull { it as? JsonObject } ?: emptyList()
            }
            else -> emptyList()
        }
    }

    private fun JsonObject.string(vararg keys: String): String {
        return keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun JsonObject.double(vararg keys: String): Double {
        return optionalDouble(*keys) ?: 0.0
    }

    private fun JsonObject.optionalDouble(vararg keys: String): Double? {
        return keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.doubleOrNull
        }
    }

    private fun JsonObject.long(vararg keys: String): Long? {
        return keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.longOrNull
        }
    }

    private fun JsonObject.boolean(vararg keys: String): Boolean {
        return keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.booleanOrNull
        } ?: false
    }

    private fun JsonObject.stringAt(vararg path: String): String {
        var current: JsonElement = this
        for (key in path) {
            current = (current as? JsonObject)?.get(key) ?: return ""
        }
        return (current as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun protocolError(obj: JsonObject): String? {
        val error = obj["error"] ?: return null
        if (error is JsonObject) {
            return error.string("reason", "message", "error").ifBlank { error.toString() }
        }
        return (error as? JsonPrimitive)?.contentOrNull
    }

    private inner class Connection(private val channel: WebSocketChannel, private val mode: ProtocolMode) {
        fun close() = channel.close()

        suspend fun call(method: String, params: List<JsonElement> = emptyList()): JsonElement {
            val id = UUID.randomUUID().toString()
            channel.send(
                buildJsonObject {
                    if (mode == ProtocolMode.DDP) put("msg", JsonPrimitive("method")) else put("jsonrpc", JsonPrimitive("2.0"))
                    put("id", JsonPrimitive(id))
                    put("method", JsonPrimitive(method))
                    put("params", JsonArray(params))
                }.toString()
            )
            return withTimeout(CALL_TIMEOUT_MS) {
                var result: JsonElement? = null
                while (result == null) {
                    val obj = receive() ?: continue
                    val message = (obj["msg"] as? JsonPrimitive)?.contentOrNull
                    // An older server speaks DDP here and will never answer this call.
                    if (mode == ProtocolMode.JSON_RPC && obj["jsonrpc"] == null && message in setOf("failed", "error")) {
                        throw IOException(context.getString(R.string.truenas_request_failed))
                    }
                    if ((obj["id"] as? JsonPrimitive)?.contentOrNull != id) continue
                    if (mode == ProtocolMode.DDP && message != "result") continue
                    protocolError(obj)?.let { throw CallFailure(it) }
                    result = obj["result"] ?: JsonNull
                }
                result
            }
        }

        suspend fun sendDdpConnect() {
            channel.send(
                buildJsonObject {
                    put("msg", JsonPrimitive("connect"))
                    put("version", JsonPrimitive("1"))
                    put("support", buildJsonArray { add(JsonPrimitive("1")) })
                }.toString()
            )
            withTimeout(CALL_TIMEOUT_MS) {
                while (true) {
                    when ((receive()?.get("msg") as? JsonPrimitive)?.contentOrNull) {
                        "connected" -> return@withTimeout
                        "failed" -> throw IOException(context.getString(R.string.truenas_request_failed))
                    }
                }
            }
        }

        private suspend fun receive(): JsonObject? =
            channel.receive().let { text -> runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() }
    }

    private data class WebSocketAttempt(val endpoint: String, val mode: ProtocolMode)
    private enum class ProtocolMode { JSON_RPC, DDP }

    companion object {
        private const val CALL_TIMEOUT_MS = 12_000L

        private val ATTEMPTS = listOf(
            WebSocketAttempt("/api/current", ProtocolMode.JSON_RPC),
            WebSocketAttempt("/api/v25.04", ProtocolMode.JSON_RPC),
            WebSocketAttempt("/websocket", ProtocolMode.DDP)
        )

        private fun extra(key: String, value: Boolean): List<JsonElement> = listOf(
            JsonArray(emptyList()),
            buildJsonObject { put("extra", buildJsonObject { put(key, JsonPrimitive(value)) }) }
        )

        private val DASHBOARD_CALLS: List<Pair<String, List<JsonElement>>> = listOf(
            "pool.query" to extra("is_upgraded", false),
            "disk.query" to extra("pools", true),
            "alert.list" to emptyList(),
            "service.query" to emptyList(),
            "sharing.smb.query" to emptyList(),
            "sharing.nfs.query" to emptyList(),
            "iscsi.target.query" to emptyList(),
            "app.query" to emptyList(),
            "vm.query" to emptyList()
        )

        fun usesSecureApiTransport(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return false
            val normalized = if ("://" in trimmed) trimmed else "https://$trimmed"
            val scheme = normalized.substringBefore("://", "").lowercase()
            return scheme == "https" || scheme == "wss"
        }
    }
}
