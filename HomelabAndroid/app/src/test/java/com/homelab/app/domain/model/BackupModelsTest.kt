package com.homelab.app.domain.model

import com.homelab.app.util.ServiceType
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import org.junit.Test

class BackupModelsTest {

    @Test
    fun `backup mapper roundtrip covers home and arr services`() {
        val covered = ServiceType.entries.filter { it != ServiceType.UNKNOWN }

        covered.forEach { type ->
            val key = BackupServiceTypeMapper.backupKey(type)
            assertEquals(type, BackupServiceTypeMapper.serviceType(key))
        }
    }

    @Test
    fun `backup entry for qbittorrent is converted back to service instance`() {
        val entry = BackupServiceEntry(
            type = "qbittorrent",
            label = "qB Home",
            url = "https://qb.local",
            username = "admin",
            password = "secret",
            fallbackUrl = "https://qb.example.com",
            allowSelfSigned = true,
            isPreferred = true
        )

        val instance = entry.toServiceInstance()

        assertNotNull(instance)
        assertEquals(ServiceType.QBITTORRENT, instance?.type)
        assertEquals("qB Home", instance?.label)
        assertEquals("admin", instance?.username)
        assertEquals("secret", instance?.password)
        assertEquals("https://qb.example.com", instance?.fallbackUrl)
        assertEquals(true, instance?.allowSelfSigned)
    }

    @Test
    fun `unknown backup key is ignored`() {
        val entry = BackupServiceEntry(
            type = "readarr",
            label = "Readarr",
            url = "https://readarr.local",
            allowSelfSigned = false,
            isPreferred = false
        )

        assertNull(entry.toServiceInstance())
    }

    @Test
    fun `linux update backup mapper uses linux_update key`() {
        assertEquals("linux_update", BackupServiceTypeMapper.backupKey(ServiceType.LINUX_UPDATE))
        assertEquals(ServiceType.LINUX_UPDATE, BackupServiceTypeMapper.serviceType("linux_update"))
    }

    @Test
    fun `truenas backup mapper accepts scale and core aliases`() {
        assertEquals("truenas", BackupServiceTypeMapper.backupKey(ServiceType.TRUENAS))
        assertEquals(ServiceType.TRUENAS, BackupServiceTypeMapper.serviceType("truenas"))
        assertEquals(ServiceType.TRUENAS, BackupServiceTypeMapper.serviceType("truenas_scale"))
        assertEquals(ServiceType.TRUENAS, BackupServiceTypeMapper.serviceType("truenas-core"))
    }

    @Test
    fun `pangolin backup mapper and conversion stay stable`() {
        assertEquals("pangolin", BackupServiceTypeMapper.backupKey(ServiceType.PANGOLIN))
        assertEquals(ServiceType.PANGOLIN, BackupServiceTypeMapper.serviceType("pangolin"))

        val entry = BackupServiceEntry(
            type = "pangolin",
            label = "Pangolin Edge",
            url = "https://pangolin.local",
            apiKey = "pangolin-key",
            fallbackUrl = "https://pangolin.example.com",
            allowSelfSigned = false,
            isPreferred = true
        )

        val instance = entry.toServiceInstance()
        assertNotNull(instance)
        assertEquals(ServiceType.PANGOLIN, instance?.type)
        assertEquals("pangolin-key", instance?.apiKey)
        assertEquals("https://pangolin.example.com", instance?.fallbackUrl)
    }

    @Test
    fun `pihole auth mode mapping stays stable`() {
        assertEquals(PiHoleAuthMode.SESSION, BackupServiceTypeMapper.piholeAuthMode("session"))
        assertEquals(PiHoleAuthMode.LEGACY, BackupServiceTypeMapper.piholeAuthMode("legacy"))
        assertNull(BackupServiceTypeMapper.piholeAuthMode("invalid"))

        assertEquals("session", BackupServiceTypeMapper.backupAuthMode(PiHoleAuthMode.SESSION))
        assertEquals("legacy", BackupServiceTypeMapper.backupAuthMode(PiHoleAuthMode.LEGACY))
        assertNull(BackupServiceTypeMapper.backupAuthMode(null))
    }

    @Test
    fun `new services and proxy headers survive a backup without sessions`() {
        val profile = AccessProfile(
            isEnabled = true,
            origins = listOf(
                OriginAccess(
                    AccessOrigin.from("https://dockge.example.test")!!,
                    headers = listOf(AccessHeader("X-Proxy", "h")),
                    cookies = listOf(AccessCookie("session", "cookie-value"))
                )
            )
        )
        for (type in listOf(com.homelab.app.util.ServiceType.SCRUTINY, com.homelab.app.util.ServiceType.DOCKGE)) {
            val entry = ServiceInstance("i", type, "X", "https://dockge.example.test", token = "t").toBackupEntry(true, profile)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val text = json.encodeToString(BackupServiceEntry.serializer(), entry)
            org.junit.Assert.assertFalse(text.contains("cookie-value"))
            val read = json.decodeFromString(BackupServiceEntry.serializer(), text)
            org.junit.Assert.assertEquals(type, read.toServiceInstance()?.type)
            org.junit.Assert.assertEquals("h", read.accessProfile?.origins?.single()?.headers?.single()?.value)
        }
    }

    @Test
    fun `a backup entry from 1_6_2 is still read`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val read = json.decodeFromString(
            BackupServiceEntry.serializer(),
            """{"type":"beszel","label":"B","url":"https://b.example.test","token":"t","allowSelfSigned":true,"password":"p","isPreferred":true}"""
        )
        org.junit.Assert.assertNull(read.accessProfile)
        org.junit.Assert.assertEquals("p", read.toServiceInstance()?.password)
    }
}
