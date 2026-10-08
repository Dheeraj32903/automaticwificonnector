package com.wificonnect.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.wificonnect.app.model.InternetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ConnectivityCheckResult(
    val state: InternetState,
    val portalUrl: String? = null
)

/**
 * Checks actual Internet connectivity and dynamically discovers captive portal endpoints.
 * Captures live HTTP 302/307 redirect Location headers from the network firewall.
 */
class ConnectivityChecker(private val context: Context) : ConnectivityInspector {

    companion object {
        private const val TAG = "ConnectivityChecker"
        // Raw IP probes require ZERO DNS lookup and immediately trigger firewall interception
        private val PROBE_URLS = listOf(
            "http://1.1.1.1",
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://neverssl.com",
            "http://8.8.8.8",
            "http://captive.apple.com/hotspot-detect.html"
        )
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /**
     * Checks internet connectivity and simultaneously discovers the active captive portal URL
     * directly from the router's HTTP interception redirect Location header.
     */
    override suspend fun checkConnectivityAndPortal(
        network: Network?,
        gatewayIp: String?
    ): ConnectivityCheckResult = withContext(Dispatchers.IO) {
        if (network == null) {
            return@withContext ConnectivityCheckResult(InternetState.NO_INTERNET, null)
        }

        // Fast-path 1: Android system network capabilities
        val caps = connectivityManager.getNetworkCapabilities(network)
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            Log.d(TAG, "[Caps] Internet access validated by Android system")
            return@withContext ConnectivityCheckResult(InternetState.CONNECTED, null)
        }

        // Fast-path 2: Probe candidate endpoints with standard client (raw IPs first to avoid DNS blocking)
        for (probeUrl in PROBE_URLS) {
            val result = probeUrlForInterception(network, probeUrl)
            if (result != null) {
                if (result.state == InternetState.CONNECTED) {
                    return@withContext result
                }
                if (!result.portalUrl.isNullOrBlank()) {
                    Log.i(TAG, "[Probe] Successfully detected captive portal: ${result.portalUrl} via $probeUrl")
                    return@withContext result
                }
            }
        }

        // If interception was detected or network lacks validated Internet, return AUTHENTICATION_REQUIRED
        val fallbackPortal = gatewayIp?.let { "http://$it:8090" }
        ConnectivityCheckResult(InternetState.AUTHENTICATION_REQUIRED, fallbackPortal)
    }

    private fun probeUrlForInterception(network: Network, testUrl: String): ConnectivityCheckResult? {
        // Use standard client for public probes to adhere to standard Android TLS security
        val client = OkHttpNetworkClient.getStandardClient(network)
        val request = okhttp3.Request.Builder()
            .url(testUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) CaptiveProbe/3.0")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                Log.d(TAG, "[Probe] $testUrl returned HTTP $code")

                when {
                    code == 204 -> {
                        ConnectivityCheckResult(InternetState.CONNECTED, null)
                    }

                    response.isRedirect || code in 300..308 -> {
                        val location = response.header("Location")
                        val discoveredPortal = if (!location.isNullOrBlank()) {
                            extractBaseUrl(location)
                        } else null
                        Log.i(TAG, "[Probe] Intercepted redirect Location: $location -> Base: $discoveredPortal")
                        ConnectivityCheckResult(InternetState.AUTHENTICATION_REQUIRED, discoveredPortal)
                    }

                    code == 200 -> {
                        val body = response.body?.string().orEmpty()
                        if (body.isEmpty() && testUrl.contains("generate_204")) {
                            ConnectivityCheckResult(InternetState.CONNECTED, null)
                        } else {
                            val extracted = extractUrlFromHtml(body)
                            ConnectivityCheckResult(InternetState.AUTHENTICATION_REQUIRED, extracted)
                        }
                    }

                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "[Probe] Probe exception for $testUrl: ${e.message}")
            null
        }
    }

    /**
     * Checks Internet connectivity state with high efficiency.
     */
    override suspend fun checkInternetConnectivity(network: Network?): InternetState = withContext(Dispatchers.IO) {
        if (network == null) return@withContext InternetState.NO_INTERNET

        // Fast-path: Check OS validated capability first
        val caps = connectivityManager.getNetworkCapabilities(network)
        if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return@withContext InternetState.CONNECTED
        }

        // Fast 204 check
        val client = OkHttpNetworkClient.getStandardClient(network)
        val request = okhttp3.Request.Builder()
            .url("http://connectivitycheck.gstatic.com/generate_204")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                if (resp.code == 204) {
                    return@withContext InternetState.CONNECTED
                }
            }
        } catch (_: Exception) {}

        // Fallback to general probe
        checkConnectivityAndPortal(network, null).state
    }

    /**
     * Dynamically discovers captive portal base URL from live network redirect or gateway candidates.
     */
    override suspend fun detectCaptivePortalUrl(network: Network?, gatewayIp: String?): String? {
        val result = checkConnectivityAndPortal(network, gatewayIp)
        return result.portalUrl ?: gatewayIp?.let { "http://$it:8090" }
    }

    /**
     * Extracts scheme, host, and port from a redirect URL (e.g. "https://10.123.1.1:8090/httpclient.html" -> "https://10.123.1.1:8090").
     */
    private fun extractBaseUrl(location: String): String {
        return try {
            val uri = Uri.parse(location.trim())
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return location.trim()
            val port = uri.port
            val portPart = if (port != -1 && port != 80 && port != 443) {
                ":$port"
            } else if (port == 8090) {
                ":8090"
            } else ""

            "$scheme://$host$portPart"
        } catch (_: Exception) {
            location.trim()
        }
    }

    private fun extractUrlFromHtml(html: String): String? {
        if (html.isBlank()) return null
        try {
            val metaPattern = Regex("""<meta[^>]*http-equiv=["']refresh["'][^>]*content=["'][^"']*url=([^"']+)["']""", RegexOption.IGNORE_CASE)
            val metaMatch = metaPattern.find(html)
            if (metaMatch != null) {
                val target = metaMatch.groupValues[1]
                return extractBaseUrl(target)
            }

            val formPattern = Regex("""<form[^>]*action=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val formMatch = formPattern.find(html)
            if (formMatch != null) {
                val target = formMatch.groupValues[1]
                if (target.startsWith("http://") || target.startsWith("https://")) {
                    return extractBaseUrl(target)
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
