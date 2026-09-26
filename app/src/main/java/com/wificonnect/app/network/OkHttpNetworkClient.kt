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
 * High-performance OkHttpClient provider optimized for speed, connection reuse,
 * and captive portal compatibility.
 */
object OkHttpNetworkClient {

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    private val permissiveHostnameVerifier = HostnameVerifier { _, _ -> true }

    // Shared connection pool: keeps TCP/TLS connections warm across probes and authentication
    private val connectionPool = ConnectionPool(10, 5, TimeUnit.MINUTES)

    // Base high-speed OkHttpClient with aggressive timeouts and connection pooling
    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier(permissiveHostnameVerifier)
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Returns an OkHttpClient bound directly to the active Wi-Fi Network's socket factory.
     * Guarantees all TCP packets travel directly across Wi-Fi without cellular/mobile-data detour.
     */
    fun getClient(network: Network?): OkHttpClient {
        return if (network != null) {
            baseClient.newBuilder()
                .socketFactory(network.socketFactory)
                .build()
        } else {
            baseClient
        }
    }
}
