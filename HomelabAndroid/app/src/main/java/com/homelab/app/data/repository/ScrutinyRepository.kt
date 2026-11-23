package com.homelab.app.data.repository

import android.content.Context
import com.homelab.app.R
import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.domain.model.ServiceInstance
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Serializable
data class ScrutinyDevice(
    val wwn: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("serial_number") val serialNumber: String? = null,
    val firmware: String? = null,
    val capacity: Long? = null,
    val label: String? = null,
    @SerialName("host_id") val hostId: String? = null,
    @SerialName("friendly_name") val friendlyName: String? = null,
    @SerialName("device_status") val deviceStatus: Int? = null,
    /** Missing on servers that cannot archive drives (before 0.8.2). */
    val archived: Boolean? = null
) {
    val title: String
        get() = listOf(friendlyName, label).firstOrNull { !it.isNullOrBlank() }
            ?: deviceName?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("/")) it else "/dev/$it" }
            ?: modelName ?: wwn ?: "?"
}

enum class ScrutinyHealth { PASSED, FAILED, UNKNOWN }

data class ScrutinyDrive(
    /** The key the server lists the drive under: a UUID since 0.9.0, the WWN before that. */
    val id: String,
    val device: ScrutinyDevice,
    val temperature: Int?,
    val powerOnHours: Int?,
    val lastSeen: Instant?
) {
    val isArchived: Boolean get() = device.archived == true
    val canArchive: Boolean get() = device.archived != null && id.isNotEmpty()

    /** [threshold] is the server's choice of which checks count: 1 SMART, 2 Scrutiny, 3 both. */
    fun health(threshold: Int): ScrutinyHealth = when {
        lastSeen == null -> ScrutinyHealth.UNKNOWN
        (device.deviceStatus ?: 0) and threshold == 0 -> ScrutinyHealth.PASSED
        else -> ScrutinyHealth.FAILED
    }
}

data class ScrutinySettings(val statusThreshold: Int = 3, val usesFahrenheit: Boolean = false) {
    fun format(celsius: Int): String =
        if (usesFahrenheit) "${(celsius * 9 / 5.0 + 32).roundToInt()} °F" else "$celsius °C"
}

data class ScrutinyTemperature(val date: Instant, val celsius: Int)

enum class ScrutinyLevel { FAILED, WARNING, PASSED }

data class ScrutinyAttribute(
    val id: String, val name: String, val value: String,
    val isCritical: Boolean, val reason: String?, val status: Int
) {
    /** Bits: 1 failed by SMART, 2 warning, 4 failed by Scrutiny. */
    fun level(threshold: Int): ScrutinyLevel = when {
        status and 1 != 0 && threshold and 1 != 0 -> ScrutinyLevel.FAILED
        status and 4 != 0 && threshold and 2 != 0 -> ScrutinyLevel.FAILED
        status and 2 != 0 && threshold and 2 != 0 -> ScrutinyLevel.WARNING
        else -> ScrutinyLevel.PASSED
    }
}

data class ScrutinyDriveDetails(val powerCycles: Int?, val attributes: List<ScrutinyAttribute>)

data class ScrutinyOverview(val drives: List<ScrutinyDrive>, val settings: ScrutinySettings) {
    val active: List<ScrutinyDrive> get() = drives.filter { !it.isArchived }
    val archived: List<ScrutinyDrive> get() = drives.filter { it.isArchived }
    val healthyCount: Int get() = active.count { health(it) == ScrutinyHealth.PASSED }
    val failedCount: Int get() = active.count { health(it) == ScrutinyHealth.FAILED }
    val hottest: Int? get() = active.mapNotNull { it.temperature }.maxOrNull()

    fun health(drive: ScrutinyDrive): ScrutinyHealth = drive.health(settings.statusThreshold)
}

/** Reads Scrutiny's replies; apart from the network so it can be tested. */
object ScrutinyParser {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable
    private data class Summary(val data: Data) {
        @Serializable
        data class Data(val summary: Map<String, Entry>)

        @Serializable
        data class Entry(val device: ScrutinyDevice, val smart: Smart? = null)

        @Serializable
        data class Smart(
            @SerialName("collector_date") val date: String? = null,
            val temp: Int? = null,
            @SerialName("power_on_hours") val powerOnHours: Int? = null
        )
    }

    @Serializable
    private data class Details(val data: Data, val metadata: Map<String, Metadata>? = null) {
        @Serializable
        data class Data(@SerialName("smart_results") val results: List<Result>? = null)

        @Serializable
        data class Result(
            @SerialName("power_cycle_count") val powerCycles: Int? = null,
            val attrs: Map<String, Attribute>? = null
        )

        @Serializable
        data class Attribute(
            val value: Long? = null,
            @SerialName("raw_value") val rawValue: Long? = null,
            @SerialName("raw_string") val rawString: String? = null,
            @SerialName("transformed_value") val transformed: Long? = null,
            val status: Int? = null,
            @SerialName("status_reason") val reason: String? = null
        )

        @Serializable
        data class Metadata(
            @SerialName("display_name") val name: String? = null,
            val critical: Boolean? = null,
            @SerialName("display_type") val type: String? = null,
            @SerialName("display_transformed") val isTransformed: Boolean? = null,
            @SerialName("transform_value_unit") val unit: String? = null
        )
    }

    @Serializable
    private data class Temperatures(val data: Data) {
        @Serializable
        data class Data(@SerialName("temp_history") val history: Map<String, List<Point>>? = null)

        @Serializable
        data class Point(val date: String, val temp: Int)
    }

    @Serializable
    private data class Settings(val settings: Values? = null) {
        @Serializable
        data class Values(val metrics: Metrics? = null, @SerialName("temperature_unit") val unit: String? = null)

        @Serializable
        data class Metrics(@SerialName("status_threshold") val threshold: Int? = null)
    }

    @Serializable
    data class ActionReply(val success: Boolean = false, val errors: List<String>? = null)

    fun drives(text: String): List<ScrutinyDrive> = json.decodeFromString<Summary>(text).data.summary
        .map { (id, entry) ->
            ScrutinyDrive(
                id, entry.device, entry.smart?.temp?.takeIf { it > 0 }, entry.smart?.powerOnHours, date(entry.smart?.date)
            )
        }
        .sortedWith(compareBy({ it.device.hostId.orEmpty() }, { it.device.title }, { it.id }))

    fun settings(text: String): ScrutinySettings {
        val values = json.decodeFromString<Settings>(text).settings
        return ScrutinySettings(
            values?.metrics?.threshold?.takeIf { it in 1..3 } ?: 3, values?.unit == "fahrenheit"
        )
    }

    /** Null when the period has no results. The server lists the newest first. */
    fun details(text: String): ScrutinyDriveDetails? {
        val details = json.decodeFromString<Details>(text)
        val latest = details.data.results?.firstOrNull() ?: return null
        val attributes = latest.attrs.orEmpty().map { (id, attribute) ->
            val info = details.metadata?.get(id)
            ScrutinyAttribute(
                id = id,
                name = info?.name?.takeIf { it.isNotBlank() } ?: id,
                value = display(attribute, info),
                isCritical = info?.critical == true,
                reason = attribute.reason?.takeIf { it.isNotBlank() },
                status = attribute.status ?: 0
            )
        }
        return ScrutinyDriveDetails(latest.powerCycles, attributes.sortedBy { it.name.lowercase() })
    }

    private fun display(attribute: Details.Attribute, info: Details.Metadata?): String = when {
        info?.type == "transformed" || info?.isTransformed == true ->
            listOfNotNull(attribute.transformed?.toString() ?: "–", info.unit?.takeIf { it.isNotBlank() }).joinToString(" ")
        info?.type == "raw" -> attribute.rawString ?: attribute.rawValue?.toString() ?: "–"
        else -> attribute.value?.toString() ?: "–"
    }

    fun temperatures(text: String, id: String): List<ScrutinyTemperature> =
        json.decodeFromString<Temperatures>(text).data.history?.get(id).orEmpty()
            .mapNotNull { point ->
                date(point.date)?.takeIf { point.temp > 0 }?.let { ScrutinyTemperature(it, point.temp) }
            }
            .sortedBy { it.date }

    fun action(text: String): ActionReply? = runCatching { json.decodeFromString<ActionReply>(text) }.getOrNull()

    /** Go writes up to nine decimals, and year 1 when there is no date. */
    fun date(text: String?): Instant? = runCatching { OffsetDateTime.parse(text.orEmpty()).toInstant() }.getOrNull()
        ?.takeIf { it.epochSecond > 0 }

    /** People often copy the address of the web page. */
    fun cleanUrl(raw: String): String =
        raw.trim().trimEnd('/').removeSuffix("/dashboard").removeSuffix("/web").trimEnd('/')

    /** Drives are sold in decimal units. */
    fun capacity(bytes: Long): String {
        var value = bytes.toDouble()
        val units = listOf("B", "kB", "MB", "GB", "TB", "PB")
        var index = 0
        while (value >= 1000 && index < units.lastIndex) {
            value /= 1000
            index++
        }
        return String.format(Locale.getDefault(), if (value >= 100 || index < 4) "%.0f %s" else "%.1f %s", value, units[index])
    }
}

class ScrutinyException(message: String) : Exception(message)

@Singleton
class ScrutinyRepository @Inject constructor(
    private val instances: ServiceInstancesRepository,
    private val clients: TlsClientSelector,
    @param:ApplicationContext private val context: Context
) {
    /** Scrutiny has no login of its own, so this only checks that its API answers. */
    suspend fun validate(url: String, fallbackUrl: String?, allowSelfSigned: Boolean) {
        ScrutinyParser.drives(call(listOfNotNull(url, fallbackUrl), null, allowSelfSigned, "/api/summary"))
    }

    suspend fun getOverview(instanceId: String): ScrutinyOverview {
        val drives = ScrutinyParser.drives(get(instanceId, "/api/summary"))
        val settings = try {
            ScrutinyParser.settings(get(instanceId, "/api/settings"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Only affects how results are shown, so a failure must not hide the drives.
            ScrutinySettings()
        }
        return ScrutinyOverview(drives, settings)
    }

    suspend fun getDetails(instanceId: String, driveId: String): ScrutinyDriveDetails {
        val path = "/api/device/${escape(driveId)}/details?duration_key="
        // The full history can be years long; only the latest result is shown.
        return ScrutinyParser.details(get(instanceId, path + "week"))
            ?: ScrutinyParser.details(get(instanceId, path + "forever"))
            ?: ScrutinyDriveDetails(null, emptyList())
    }

    suspend fun getTemperatures(instanceId: String, driveId: String, duration: String): List<ScrutinyTemperature> =
        ScrutinyParser.temperatures(get(instanceId, "/api/summary/temp?duration_key=$duration"), driveId)

    suspend fun setArchived(instanceId: String, driveId: String, archived: Boolean) =
        post(instanceId, "/api/device/${escape(driveId)}/${if (archived) "archive" else "unarchive"}")

    suspend fun sendTestNotification(instanceId: String) = post(instanceId, "/api/health/notify")

    private fun escape(id: String): String = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private suspend fun get(instanceId: String, path: String): String {
        val instance = instance(instanceId)
        return call(listOfNotNull(instance.url, instance.fallbackUrl), instance.id, instance.allowSelfSigned, path)
    }

    private suspend fun post(instanceId: String, path: String) {
        val instance = instance(instanceId)
        val text = call(
            listOfNotNull(instance.url, instance.fallbackUrl), instance.id, instance.allowSelfSigned, path, isPost = true
        )
        val reply = ScrutinyParser.action(text)
        if (reply?.success != true) throw failure(reply)
    }

    private fun failure(reply: ScrutinyParser.ActionReply?) = ScrutinyException(
        reply?.errors.orEmpty().joinToString("\n").ifBlank { context.getString(R.string.scrutiny_action_failed) }
    )

    private suspend fun instance(id: String): ServiceInstance =
        instances.getInstance(id) ?: throw IllegalArgumentException("Scrutiny instance not found")

    private suspend fun call(
        bases: List<String>, instanceId: String?, allowSelfSigned: Boolean, path: String, isPost: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        var firstError: IOException? = null
        for (base in bases.map { ScrutinyParser.cleanUrl(it) }.filter { it.isNotEmpty() }.distinct()) {
            try {
                val request = Request.Builder().url(base + path).header("Accept", "application/json")
                // Routing by instance would undo the cleaned address, so only the proxy is told about it.
                request.header("X-Homelab-Bypass", "true")
                instanceId?.let { request.header("X-Homelab-Instance-Id", it) }
                if (isPost) request.post(ByteArray(0).toRequestBody(null))
                clients.forAllowSelfSigned(allowSelfSigned).newCall(request.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) return@withContext text
                    if (isPost) ScrutinyParser.action(text)?.let { throw failure(it) }
                    throw IllegalStateException("${response.code}: ${response.message}")
                }
            } catch (error: IOException) {
                if (firstError == null) firstError = error
            }
        }
        throw firstError ?: IOException("No address")
    }
}
