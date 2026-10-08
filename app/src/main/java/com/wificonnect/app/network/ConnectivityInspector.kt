package com.wificonnect.app.network

import android.net.Network
import com.wificonnect.app.model.InternetState

/**
 * Interface defining Internet connectivity validation and captive portal detection.
 */
interface ConnectivityInspector {
    suspend fun checkInternetConnectivity(network: Network?): InternetState
    suspend fun detectCaptivePortalUrl(network: Network?, gatewayIp: String?): String?
    suspend fun checkConnectivityAndPortal(network: Network?, gatewayIp: String?): ConnectivityCheckResult
}
