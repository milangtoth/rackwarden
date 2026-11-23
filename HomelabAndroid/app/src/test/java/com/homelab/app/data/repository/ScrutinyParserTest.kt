package com.homelab.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrutinyParserTest {
    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun summary(id: String, device: String, smart: String? = """{"collector_date":"2026-01-02T03:04:05.123456789Z","temp":34,"power_on_hours":48}""") =
        """{"success":true,"data":{"summary":{"$id":{"device":{$device}${smart?.let { ",\"smart\":$it" }.orEmpty()}}}}}"""

    @Test
    fun `drives from current and older servers`() {
        val current = ScrutinyParser.drives(summary(uuid, """"scrutiny_uuid":"$uuid","device_name":"sda","device_status":0,"archived":false""")).single()
        assertEquals(uuid, current.id)
        assertEquals("/dev/sda", current.device.title)
        assertEquals(34, current.temperature)
        assertTrue(current.canArchive)
        assertNotNull(current.lastSeen)
        val older = ScrutinyParser.drives(summary("0x5000c500673e6b5f", """"wwn":"0x5000c500673e6b5f","device_name":"sda"""")).single()
        assertFalse(older.canArchive)
    }

    @Test
    fun `status follows the server's threshold`() {
        val drive = ScrutinyParser.drives(summary(uuid, """"device_name":"sda","device_status":2""")).single()
        assertEquals(ScrutinyHealth.PASSED, drive.health(1))
        assertEquals(ScrutinyHealth.FAILED, drive.health(3))
        val settings = ScrutinyParser.settings("""{"settings":{"temperature_unit":"fahrenheit","metrics":{"status_threshold":1}}}""")
        assertEquals(1, settings.statusThreshold)
        assertEquals("212 °F", settings.format(100))
        val unseen = ScrutinyParser.drives(summary(uuid, """"device_name":"sda"""", null)).single()
        assertEquals(ScrutinyHealth.UNKNOWN, unseen.health(3))
    }

    @Test
    fun `attribute levels`() {
        fun level(status: Int, threshold: Int) = ScrutinyAttribute("1", "", "", false, null, status).level(threshold)
        assertEquals(ScrutinyLevel.PASSED, level(0, 3))
        assertEquals(ScrutinyLevel.FAILED, level(1, 3))
        assertEquals(ScrutinyLevel.WARNING, level(2, 3))
        assertEquals(ScrutinyLevel.FAILED, level(4, 3))
        assertEquals(ScrutinyLevel.PASSED, level(4, 1))
    }

    @Test
    fun `details show readable values`() {
        assertNull(ScrutinyParser.details("""{"data":{"device":{},"smart_results":null},"metadata":null}"""))
        val details = ScrutinyParser.details(
            """{"data":{"smart_results":[{"power_cycle_count":9,"attrs":{"194":{"value":66,"transformed_value":34,"status":0},
            "5":{"value":100,"raw_value":7,"raw_string":"7","status":4,"status_reason":"too many"}}},{"attrs":{}}]},
            "metadata":{"194":{"display_name":"Temperature","display_type":"transformed","transform_value_unit":"°C"},
            "5":{"display_name":"Reallocated Sectors","critical":true,"display_type":"raw"}}}"""
        )!!
        assertEquals(listOf("Reallocated Sectors" to "7", "Temperature" to "34 °C"), details.attributes.map { it.name to it.value })
        assertTrue(details.attributes.first().isCritical)
        assertEquals(9, details.powerCycles)
    }

    @Test
    fun `temperatures are for the drive and in order`() {
        val found = ScrutinyParser.temperatures(
            """{"data":{"temp_history":{"$uuid":[{"date":"2026-01-02T00:00:00Z","temp":35},{"date":"2026-01-01T00:00:00Z","temp":33},{"date":"2026-01-03T00:00:00Z","temp":0}],"x":[]}}}""",
            uuid
        )
        assertEquals(listOf(33, 35), found.map { it.celsius })
    }

    @Test
    fun `dates and addresses`() {
        assertNull(ScrutinyParser.date("0001-01-01T00:00:00Z"))
        assertNull(ScrutinyParser.date("nonsense"))
        assertNotNull(ScrutinyParser.date("2026-01-02T03:04:05.5-07:00"))
        assertEquals("https://s.example.test", ScrutinyParser.cleanUrl("https://s.example.test/web/dashboard/"))
        assertEquals("https://s.example.test/base", ScrutinyParser.cleanUrl("https://s.example.test/base/"))
    }
}
