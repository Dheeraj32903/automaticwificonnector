package com.wificonnect.app.model

/**
 * Represents captive portal credentials.
 * The password is only held in memory during the login operation and never logged or serialized to plaintext.
 */
data class UserCredentials(
    val username: String,
    val password: String,
    val portalUrl: String? = null,
    val ssid: String? = null
) {
    override fun toString(): String {
        return "UserCredentials(username='$username', portalUrl=$portalUrl, ssid=$ssid, password=[PROTECTED])"
    }
}
