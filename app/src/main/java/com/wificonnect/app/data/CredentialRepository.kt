package com.wificonnect.app.data

import com.wificonnect.app.model.UserCredentials
import com.wificonnect.app.model.WifiProfile

/**
 * Interface defining access to secure credentials and persistent Wi-Fi profiles.
 */
interface CredentialRepository {
    fun hasCredentials(): Boolean
    fun getSavedUsername(): String?
    fun getCredentials(): UserCredentials?
    fun getSavedPortalUrl(): String?
    fun isAutoLoginEnabled(): Boolean
    fun setAutoLoginEnabled(enabled: Boolean)
    fun getWifiProfile(ssid: String): WifiProfile?
    fun saveWifiProfile(profile: WifiProfile)
    fun recordLoginSuccess(ssid: String, portalUrl: String, durationMs: Long)
    fun recordLoginFailure(ssid: String)
    fun clearProfileForSsid(ssid: String)
    fun clearPortalUrl()
    fun clearCredentials()
}
