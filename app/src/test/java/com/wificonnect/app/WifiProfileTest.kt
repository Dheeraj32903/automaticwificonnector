package com.wificonnect.app

import com.wificonnect.app.model.WifiProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying WifiProfile behavior, staleness detection, and metrics.
 */
class WifiProfileTest {

    @Test
    fun testNewProfileIsNotStaleAndNotKnown() {
        val profile = WifiProfile(
            ssid = "CampusWiFi",
            portalUrl = null,
            successCount = 0,
            failureCount = 0
        )
        assertFalse(profile.isStale)
        assertFalse(profile.isKnown)
    }

    @Test
    fun testSuccessfulProfileIsKnown() {
        val profile = WifiProfile(
            ssid = "CampusWiFi",
            portalUrl = "http://172.16.1.1:8090",
            lastSuccessfulLogin = System.currentTimeMillis(),
            successCount = 5,
            failureCount = 0,
            averageLoginTimeMs = 85L
        )
        assertTrue(profile.isKnown)
        assertFalse(profile.isStale)
        assertEquals("http://172.16.1.1:8090", profile.portalUrl)
        assertEquals(5, profile.successCount)
        assertEquals(85L, profile.averageLoginTimeMs)
    }

    @Test
    fun testProfileMarkedStaleAfterThreeFailures() {
        val profile = WifiProfile(
            ssid = "CampusWiFi",
            portalUrl = "http://172.16.1.1:8090",
            successCount = 10,
            failureCount = 3
        )
        assertTrue("Profile should be stale after 3 consecutive failures", profile.isStale)
        assertFalse("Stale profile should NOT be considered known for fast-path", profile.isKnown)
    }

    @Test
    fun testBlankPortalUrlIsNotKnown() {
        val profile = WifiProfile(
            ssid = "CampusWiFi",
            portalUrl = "   ",
            successCount = 2,
            failureCount = 0
        )
        assertFalse(profile.isKnown)
    }
}
