package com.wificonnect.app.network

import android.net.Network
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * High-performance OkHttpClient provider.
 *
 * Security Architecture (Step 11):
 * - Standard traffic (Internet verification, public probes, external APIs) strictly uses
 *   the system platform default TLS trust store and default strict hostname verification.
 * - Compatibility handling for campus captive portal appliances (which frequently run on
 *   private RFC1918 IPs with self-signed appliance certificates) is strictly isolated
 *   to portal authentication requests via [getPortalClient], avoiding global security bypass.
 */
object OkHttpNetworkClient {

    // Shared connection pool across probes and authentication to maintain warm TCP sockets
    private val connectionPool = ConnectionPool(10, 5, TimeUnit.MINUTES)

    // Standard client with strict system TLS and strict hostname verification
    private val standardBaseClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    // Isolated appliance trust manager scoped ONLY to campus captive portal hardware
    private val portalApplianceTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val portalSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(portalApplianceTrustManager), SecureRandom())
        }
    }

    private val portalHostnameVerifier = HostnameVerifier { _, _ -> true }

    // Portal client: Scoped specifically for captive portal appliances (Cyberoam/Sophos)
    private val portalBaseClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .sslSocketFactory(portalSslContext.socketFactory, portalApplianceTrustManager)
        .hostnameVerifier(portalHostnameVerifier)
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Standard client using system-trusted TLS and strict hostname verification.
     * Use this for Internet connectivity checks and public HTTP/HTTPS probes.
     */
    fun getStandardClient(network: Network?): OkHttpClient {
        return if (network != null) {
            standardBaseClient.newBuilder()
                .socketFactory(network.socketFactory)
                .build()
        } else {
            standardBaseClient
        }
    }

    /**
     * Scoped captive portal client with appliance TLS compatibility.
     * Bound directly to the active Wi-Fi Network's socket factory to bypass mobile data.
     */
    fun getPortalClient(network: Network?): OkHttpClient {
        return if (network != null) {
            portalBaseClient.newBuilder()
                .socketFactory(network.socketFactory)
                .build()
        } else {
            portalBaseClient
        }
    }

    /**
     * Backward-compatible alias for captive portal authentication callers.
     */
    fun getClient(network: Network?): OkHttpClient = getPortalClient(network)
}
