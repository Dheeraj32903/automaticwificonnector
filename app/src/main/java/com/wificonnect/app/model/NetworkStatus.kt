package com.wificonnect.app.model

enum class WifiState {
    DISCONNECTED,
    CONNECTED
}

enum class InternetState {
    CHECKING,
    CONNECTED,
    AUTHENTICATION_REQUIRED,
    NO_INTERNET
}

data class NetworkStatus(
    val wifiState: WifiState = WifiState.DISCONNECTED,
    val ssid: String = "No Wi-Fi",
    val internetState: InternetState = InternetState.CHECKING,
    val captivePortalUrl: String? = null,
    val gatewayIp: String? = null
)
