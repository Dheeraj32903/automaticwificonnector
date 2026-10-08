package com.wificonnect.app.model

/**
 * Represents a persistent network profile for a Wi-Fi network.
 * Retains connection intelligence to allow instant fast-path authentication.
 */
data class WifiProfile(
    val ssid: String,
    val portalUrl: String?,
    val portalType: String? = "Cyberoam",
    val lastSuccessfulLogin: Long = 0L,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val averageLoginTimeMs: Long = 0L
) {
    /**
     * Profile is considered stale if consecutive failures reach or exceed threshold,
     * triggering fresh portal discovery instead of repeatedly hitting a dead endpoint.
     */
    val isStale: Boolean
        get() = failureCount >= 3

    /**
     * Indicates whether this profile has successfully connected previously.
     */
    val isKnown: Boolean
        get() = !portalUrl.isNullOrBlank() && successCount > 0 && !isStale
}
