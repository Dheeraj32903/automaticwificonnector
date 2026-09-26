package com.wificonnect.app.network

import android.net.Network
import com.wificonnect.app.model.LoginResult
import com.wificonnect.app.model.UserCredentials

/**
 * Interface defining captive portal authentication operations.
 * Allows multiple captive portal types (Cyberoam, Fortinet, Aruba, etc.) to be plugged in.
 */
interface CaptivePortalLogin {

    /**
     * Attempts captive portal authentication.
     *
     * @param credentials Saved user credentials (username, password, etc.)
     * @param targetPortalUrl The detected or configured portal base URL (e.g. "http://172.16.1.1:8090")
     * @param network Active Wi-Fi network to route sockets through
     */
    suspend fun login(
        credentials: UserCredentials,
        targetPortalUrl: String?,
        network: Network?,
        candidateUrls: List<String> = emptyList()
    ): LoginResult

    /**
     * Logs out of captive portal to release concurrent login sessions (Cyberoam mode 193).
     */
    suspend fun logout(
        username: String,
        targetPortalUrl: String?,
        network: Network?
    ): Boolean
}
