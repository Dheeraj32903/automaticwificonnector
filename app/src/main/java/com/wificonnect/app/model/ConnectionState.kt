package com.wificonnect.app.model

/**
 * Detailed failure reasons for the connection state machine.
 */
sealed class ConnectionFailureReason {
    data class AuthFailed(val message: String) : ConnectionFailureReason()
    data class PortalUnavailable(val message: String) : ConnectionFailureReason()
    object NetworkChanged : ConnectionFailureReason()
    data class DiscoveryFailed(val message: String) : ConnectionFailureReason()
    data class SessionLimit(val message: String, val details: String? = null) : ConnectionFailureReason()
    data class VerificationFailed(val message: String) : ConnectionFailureReason()
    data class ChallengeRequired(val message: String, val challengeUrl: String? = null) : ConnectionFailureReason()
    data class Generic(val message: String) : ConnectionFailureReason()
}

/**
 * Formal Connection State Machine representing the lifecycle of a Wi-Fi connection attempt:
 *
 * DISCONNECTED
 *       ↓
 * WIFI_CONNECTED
 *       ↓
 * IDENTIFYING_NETWORK
 *       ↓
 * AUTHENTICATING
 *       ↓
 * AUTHENTICATED
 *       ↓
 * VERIFYING
 *       ↓
 * CONNECTED
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()

    data class WifiConnected(
        val ssid: String,
        val isKnown: Boolean = false
    ) : ConnectionState()

    data class IdentifyingNetwork(
        val ssid: String
    ) : ConnectionState()

    data class Authenticating(
        val ssid: String,
        val portalUrl: String?,
        val attempt: Int = 1,
        val isFastPath: Boolean = false
    ) : ConnectionState()

    data class Authenticated(
        val ssid: String,
        val portalUrl: String?,
        val authDurationMs: Long,
        val isFastPath: Boolean = false
    ) : ConnectionState()

    data class Verifying(
        val ssid: String,
        val portalUrl: String?,
        val isFastPath: Boolean = false
    ) : ConnectionState()

    data class Connected(
        val ssid: String,
        val portalUrl: String?,
        val metrics: ConnectionMetrics? = null
    ) : ConnectionState()

    data class Failed(
        val ssid: String,
        val reason: ConnectionFailureReason,
        val canRetry: Boolean = false,
        val attempt: Int = 1
    ) : ConnectionState()
}
