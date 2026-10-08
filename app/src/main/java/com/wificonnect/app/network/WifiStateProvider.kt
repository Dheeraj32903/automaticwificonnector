package com.wificonnect.app.network

import android.net.Network

/**
 * Interface defining Wi-Fi state queries and process network binding.
 */
interface WifiStateProvider {
    fun getActiveWifiNetwork(): Network?
    fun isWifiConnected(): Boolean
    fun getWifiSsid(): String
    fun getGatewayIp(): String?
    fun getCandidatePortalUrls(primaryUrl: String? = null): List<String>
    fun bindProcessToWifi(): Boolean
    fun unbindProcess()
}
