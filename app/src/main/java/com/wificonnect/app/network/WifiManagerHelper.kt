package com.wificonnect.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address

/**
 * Helper for inspecting Wi-Fi state, active Wi-Fi Network instance, SSID, and gateway IP.
 */
class WifiManagerHelper(private val context: Context) : WifiStateProvider {

    companion object {
        private const val TAG = "WifiManagerHelper"
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    /**
     * Finds the active Wi-Fi Network object.
     */
    @Suppress("DEPRECATION")
    override fun getActiveWifiNetwork(): Network? {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return activeNetwork
        }

        // Search through all networks in case Wi-Fi is active but not primary route
        for (net in connectivityManager.allNetworks) {
            val netCaps = connectivityManager.getNetworkCapabilities(net) ?: continue
            if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return net
            }
        }
        return null
    }

    /**
     * Checks whether the device is currently connected to Wi-Fi.
     */
    override fun isWifiConnected(): Boolean {
        val wifiNet = getActiveWifiNetwork() ?: return false
        val caps = connectivityManager.getNetworkCapabilities(wifiNet) ?: return false
        val connected = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (connected) {
            Log.d(TAG, "[WiFi] Connected")
        } else {
            Log.d(TAG, "[WiFi] Disconnected")
        }
        return connected
    }

    /**
     * Obtains the current Wi-Fi SSID where Android permissions allow it.
     * Gracefully handles Android 10+ "<unknown ssid>" when location permission is not granted.
     */
    @Suppress("DEPRECATION")
    override fun getWifiSsid(): String {
        try {
            val wifiInfo: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val wifiNet = getActiveWifiNetwork()
                val caps = wifiNet?.let { connectivityManager.getNetworkCapabilities(it) }
                caps?.transportInfo as? WifiInfo ?: wifiManager?.connectionInfo
            } else {
                wifiManager?.connectionInfo
            }

            val rawSsid = wifiInfo?.ssid
            if (!rawSsid.isNullOrBlank() && rawSsid != "<unknown ssid>") {
                val cleanSsid = rawSsid.trim('"')
                Log.d(TAG, "[WiFi] Current network detected: $cleanSsid")
                return cleanSsid
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error obtaining Wi-Fi SSID", e)
        }
        return "Wi-Fi Network"
    }

    /**
     * Retrieves the default gateway IPv4 address from LinkProperties of the Wi-Fi network.
     * On university networks, the Cyberoam/Sophos portal is typically at http://<gatewayIp>:8090.
     */
    override fun getGatewayIp(): String? {
        val wifiNet = getActiveWifiNetwork() ?: return null
        val linkProps: LinkProperties = connectivityManager.getLinkProperties(wifiNet) ?: return null

        // 1. Primary check: Default route with valid IPv4 gateway
        for (route in linkProps.routes) {
            if (route.isDefaultRoute && route.gateway is Inet4Address) {
                val ip = route.gateway?.hostAddress
                if (!ip.isNullOrBlank() && ip != "0.0.0.0") {
                    Log.d(TAG, "[Gateway] Detected from default route: $ip")
                    return ip
                }
            }
        }

        // 2. Secondary check: Any non-zero IPv4 gateway in routes
        for (route in linkProps.routes) {
            val gateway = route.gateway
            if (gateway is Inet4Address) {
                val ip = gateway.hostAddress
                if (!ip.isNullOrBlank() && ip != "0.0.0.0") {
                    Log.d(TAG, "[Gateway] Detected from secondary route: $ip")
                    return ip
                }
            }
        }

        // 3. Tertiary fallback via WifiManager DHCP info
        try {
            @Suppress("DEPRECATION")
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val g = dhcp.gateway
                val ip = "${g and 0xFF}.${(g shr 8) and 0xFF}.${(g shr 16) and 0xFF}.${(g shr 24) and 0xFF}"
                if (ip != "0.0.0.0") {
                    Log.d(TAG, "[Gateway] Detected from DHCP info: $ip")
                    return ip
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking DHCP gateway", e)
        }

        // 4. Check DHCP server address on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dhcpServer = linkProps.dhcpServerAddress?.hostAddress
            if (!dhcpServer.isNullOrBlank() && dhcpServer != "0.0.0.0") {
                Log.d(TAG, "[Gateway] Detected from DHCP server address: $dhcpServer")
                return dhcpServer
            }
        }

        // 5. Check local DNS servers (in campus LANs, local DNS is commonly the gateway router)
        for (dns in linkProps.dnsServers) {
            if (dns is Inet4Address && !dns.isLoopbackAddress && dns.isSiteLocalAddress) {
                val ip = dns.hostAddress
                if (!ip.isNullOrBlank() && ip != "0.0.0.0") {
                    Log.d(TAG, "[Gateway] Detected from local DNS server: $ip")
                    return ip
                }
            }
        }

        // 6. Check DHCP server address in dhcpInfo
        try {
            @Suppress("DEPRECATION")
            val server = wifiManager?.dhcpInfo?.serverAddress ?: 0
            if (server != 0) {
                val ip = "${server and 0xFF}.${(server shr 8) and 0xFF}.${(server shr 16) and 0xFF}.${(server shr 24) and 0xFF}"
                if (ip != "0.0.0.0") {
                    Log.d(TAG, "[Gateway] Detected from DHCP server info: $ip")
                    return ip
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Retrieves all IPv4 DNS server addresses configured on the Wi-Fi interface.
     */
    fun getDnsServers(): List<String> {
        val wifiNet = getActiveWifiNetwork() ?: return emptyList()
        val linkProps = connectivityManager.getLinkProperties(wifiNet) ?: return emptyList()
        val list = mutableListOf<String>()
        for (dns in linkProps.dnsServers) {
            if (dns is Inet4Address && !dns.isLoopbackAddress) {
                val ip = dns.hostAddress
                if (!ip.isNullOrBlank() && ip != "0.0.0.0") {
                    list.add(ip)
                }
            }
        }
        return list
    }

    /**
     * Binds the current application process to the active Wi-Fi network.
     * Prevents Android from routing captive portal DNS and HTTP requests through Mobile Data (Cellular).
     */
    override fun bindProcessToWifi(): Boolean {
        return try {
            val wifiNet = getActiveWifiNetwork() ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val bound = connectivityManager.bindProcessToNetwork(wifiNet)
                Log.d(TAG, "[Network] Bound process to Wi-Fi: $bound")
                bound
            } else {
                @Suppress("DEPRECATION")
                val bound = ConnectivityManager.setProcessDefaultNetwork(wifiNet)
                Log.d(TAG, "[Network] Bound process (legacy) to Wi-Fi: $bound")
                bound
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind process to Wi-Fi", e)
            false
        }
    }

    /**
     * Restores default Android routing after Wi-Fi operations complete.
     */
    override fun unbindProcess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.bindProcessToNetwork(null)
            } else {
                @Suppress("DEPRECATION")
                ConnectivityManager.setProcessDefaultNetwork(null)
            }
        } catch (_: Exception) {}
    }

    /**
     * Assembles a prioritized, deduplicated list of portal candidate base URLs.
     * Combines universal Cyberoam/Sophos appliance IPs, gateway IPs, DNS server IPs,
     * and campus subnet roots.
     */
    override fun getCandidatePortalUrls(primaryUrl: String?): List<String> {
        val candidates = LinkedHashSet<String>()

        // 1. Primary candidate (if supplied)
        if (!primaryUrl.isNullOrBlank()) {
            val trimmed = primaryUrl.trim().removeSuffix("/")
            candidates.add(trimmed)
            if (trimmed.startsWith("http://", ignoreCase = true)) {
                candidates.add(trimmed.replaceFirst("http://", "https://", ignoreCase = true))
            } else if (trimmed.startsWith("https://", ignoreCase = true)) {
                candidates.add(trimmed.replaceFirst("https://", "http://", ignoreCase = true))
            }
        }

        // 2. Cyberoam universal appliance IPs (standard across Indian university campuses)
        candidates.add("http://172.16.16.16:8090")
        candidates.add("https://172.16.16.16:8090")

        // 3. Active Wi-Fi Gateway IP on port 8090
        val gateway = getGatewayIp()
        if (!gateway.isNullOrBlank()) {
            candidates.add("http://$gateway:8090")
            candidates.add("https://$gateway:8090")

            // Subnet root variants (e.g. 10.123.1.1 -> 10.123.0.1)
            val parts = gateway.split(".")
            if (parts.size == 4) {
                candidates.add("http://${parts[0]}.${parts[1]}.0.1:8090")
                candidates.add("https://${parts[0]}.${parts[1]}.0.1:8090")
            }
        }

        // 4. DNS servers on the Wi-Fi connection
        for (dns in getDnsServers()) {
            candidates.add("http://$dns:8090")
            candidates.add("https://$dns:8090")
        }

        // 5. Common campus router defaults
        candidates.add("http://10.0.0.1:8090")
        candidates.add("https://10.0.0.1:8090")
        candidates.add("http://10.1.1.1:8090")
        candidates.add("https://10.1.1.1:8090")

        // 6. Sophos User Portal port
        candidates.add("https://172.16.16.16:4444")
        candidates.add("http://172.16.16.16:4444")

        return candidates.toList()
    }
}

