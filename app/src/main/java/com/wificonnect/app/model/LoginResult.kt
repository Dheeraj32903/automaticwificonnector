package com.wificonnect.app.model

/**
 * Result of a captive portal authentication attempt.
 */
sealed class LoginResult {
    data class Success(
        val message: String = "Connected ✓",
        val portalUrl: String? = null
    ) : LoginResult()

    data class InvalidCredentials(
        val message: String = "Invalid username or password.",
        val portalUrl: String? = null
    ) : LoginResult()

    data class LimitExceeded(
        val message: String = "Login limit exceeded.",
        val details: String? = null,
        val portalUrl: String? = null
    ) : LoginResult()

    data class ChallengeRequired(
        val message: String = "Additional authentication required.",
        val state: String? = null,
        val portalUrl: String? = null
    ) : LoginResult()

    data class PortalUnavailable(
        val message: String = "Authentication portal unavailable."
    ) : LoginResult()

    data class NetworkError(
        val message: String = "Unable to connect to the authentication portal."
    ) : LoginResult()

    data class UnknownError(
        val message: String,
        val portalUrl: String? = null
    ) : LoginResult()
}

