package com.wificonnect.app

import com.wificonnect.app.model.ConnectionMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying connection performance measurement and metrics formatting.
 */
class ConnectionMetricsTest {

    @Test
    fun testDurationsCalculation() {
        val metrics = ConnectionMetrics(
            startTimeMs = 1000L,
            wifiDetectedTimeMs = 1000L,
            networkIdentifiedTimeMs = 1015L,
            profileLookupDurationMs = 2L,
            discoveryDurationMs = 0L,
            authStartMs = 1020L,
            authEndMs = 1090L,
            verificationStartMs = 1092L,
            verificationEndMs = 1115L,
            isFastPath = true
        )

        assertEquals(15L, metrics.networkIdentificationDurationMs)
        assertEquals(70L, metrics.authDurationMs)
        assertEquals(23L, metrics.verificationDurationMs)
        assertEquals(115L, metrics.totalDurationMs)

        val summary = metrics.formatSummary()
        assertTrue(summary.contains("Fast-Path"))
        assertTrue(summary.contains("Auth: 70ms"))
        assertTrue(summary.contains("Verify: 23ms"))
        assertTrue(summary.contains("Total: 115ms"))
    }

    @Test
    fun testDetailedBreakdownContainsAllMetrics() {
        val metrics = ConnectionMetrics(
            startTimeMs = 1000L,
            wifiDetectedTimeMs = 1000L,
            networkIdentifiedTimeMs = 1010L,
            profileLookupDurationMs = 3L,
            discoveryDurationMs = 45L,
            authStartMs = 1060L,
            authEndMs = 1140L,
            verificationStartMs = 1145L,
            verificationEndMs = 1170L,
            isFastPath = false,
            attemptsCount = 2
        )

        val details = metrics.formatDetailedBreakdown()
        assertTrue(details.contains("Wi-Fi identification: 10ms"))
        assertTrue(details.contains("Cached profile lookup: 3ms"))
        assertTrue(details.contains("Portal discovery: 45ms"))
        assertTrue(details.contains("Authentication: 80ms"))
        assertTrue(details.contains("Verification: 25ms"))
        assertTrue(details.contains("Attempts: 2"))
    }
}
