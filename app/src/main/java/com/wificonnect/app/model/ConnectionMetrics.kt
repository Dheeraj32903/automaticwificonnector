package com.wificonnect.app.model

/**
 * Tracks granular performance timestamps and latencies for a connection cycle.
 * Used to identify network and processing bottlenecks without exposing credentials.
 */
data class ConnectionMetrics(
    var startTimeMs: Long = 0L,
    var wifiDetectedTimeMs: Long = 0L,
    var networkIdentifiedTimeMs: Long = 0L,
    var profileLookupDurationMs: Long = 0L,
    var discoveryDurationMs: Long = 0L,
    var authStartMs: Long = 0L,
    var authEndMs: Long = 0L,
    var verificationStartMs: Long = 0L,
    var verificationEndMs: Long = 0L,
    var isFastPath: Boolean = false,
    var attemptsCount: Int = 1
) {
    val networkIdentificationDurationMs: Long
        get() = if (networkIdentifiedTimeMs >= wifiDetectedTimeMs && wifiDetectedTimeMs > 0) {
            networkIdentifiedTimeMs - wifiDetectedTimeMs
        } else 0L

    val authDurationMs: Long
        get() = if (authEndMs >= authStartMs && authStartMs > 0) {
            authEndMs - authStartMs
        } else 0L

    val verificationDurationMs: Long
        get() = if (verificationEndMs >= verificationStartMs && verificationStartMs > 0) {
            verificationEndMs - verificationStartMs
        } else 0L

    val totalDurationMs: Long
        get() = if (authEndMs > 0 && startTimeMs > 0) {
            val end = if (verificationEndMs > 0) verificationEndMs else authEndMs
            end - startTimeMs
        } else 0L

    fun formatSummary(): String {
        return buildString {
            if (isFastPath) append("⚡ Fast-Path | ")
            append("Auth: ${authDurationMs}ms")
            if (verificationDurationMs > 0) {
                append(" | Verify: ${verificationDurationMs}ms")
            }
            if (totalDurationMs > 0) {
                append(" | Total: ${totalDurationMs}ms")
            }
            if (attemptsCount > 1) {
                append(" (${attemptsCount} attempts)")
            }
        }
    }

    fun formatDetailedBreakdown(): String {
        return """
            Latency Breakdown:
            - Wi-Fi identification: ${networkIdentificationDurationMs}ms
            - Cached profile lookup: ${profileLookupDurationMs}ms
            - Portal discovery: ${discoveryDurationMs}ms
            - Authentication: ${authDurationMs}ms
            - Verification: ${verificationDurationMs}ms
            - Total time: ${totalDurationMs}ms (Fast-Path: $isFastPath, Attempts: $attemptsCount)
        """.trimIndent()
    }
}
