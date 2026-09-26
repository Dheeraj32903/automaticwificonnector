package com.wificonnect.app.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wificonnect.app.data.SecureCredentialStore
import com.wificonnect.app.model.InternetState
import com.wificonnect.app.model.LoginResult
import com.wificonnect.app.model.UserCredentials
import com.wificonnect.app.network.CaptivePortalLogin
import com.wificonnect.app.network.ConnectivityChecker
import com.wificonnect.app.network.CyberoamCaptivePortalLogin
import com.wificonnect.app.network.WifiManagerHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StatusType {
    IDLE,
    READY,
    CONNECTING,
    SUCCESS,
    ERROR_AUTH,
    ERROR_LIMIT,
    CHALLENGE,
    ERROR_NETWORK
}

data class MainUiState(
    val wifiConnected: Boolean = false,
    val ssid: String = "No Wi-Fi",
    val wifiStatusText: String = "Disconnected",
    val internetStatusText: String = "Checking...",
    val internetState: InternetState = InternetState.CHECKING,
    val portalUrl: String? = null,
    val hasCredentials: Boolean = false,
    val savedUsername: String? = null,
    val autoLoginEnabled: Boolean = true,
    val statusMessage: String = "Ready",
    val statusDetails: String? = null,
    val statusType: StatusType = StatusType.IDLE,
    val isLoading: Boolean = false,
    val challengeUrl: String? = null,
    val openCredentialsDialogEvent: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val credentialStore = SecureCredentialStore(application)
    private val wifiHelper = WifiManagerHelper(application)
    private val connectivityChecker = ConnectivityChecker(application)
    private val captivePortalLogin: CaptivePortalLogin = CyberoamCaptivePortalLogin()

    // Flag to prevent auto-login loop when the user explicitly clicks DISCONNECT / LOGOUT
    private var userExplicitlyLoggedOut = false

    private val _uiState = MutableStateFlow(
        MainUiState(autoLoginEnabled = credentialStore.isAutoLoginEnabled())
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "[NetworkCallback] Wi-Fi network available")
            // Reset explicit logout when connecting to a new network
            userExplicitlyLoggedOut = false
            viewModelScope.launch { refreshState() }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "[NetworkCallback] Wi-Fi network lost")
            viewModelScope.launch { refreshState() }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            viewModelScope.launch { refreshState() }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            Log.d(TAG, "[NetworkCallback] Link properties changed (new gateway/IP)")
            viewModelScope.launch { refreshState() }
        }
    }

    init {
        registerNetworkCallback()
        refreshState()
    }

    private fun registerNetworkCallback() {
        try {
            val connectivityManager = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            Log.d(TAG, "Registered Wi-Fi state listener")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Resolves the captive portal base URL for the active network.
     * Prevents locking to a stale gateway IP when moving from Wi-Fi to Wi-Fi (e.g. Academic vs Hostel).
     */
    /**
     * Resolves the captive portal base URL for the active network.
     * Prevents locking to a stale gateway IP when moving from Wi-Fi to Wi-Fi (e.g. Academic vs Hostel).
     */
    private suspend fun resolvePortalUrl(
        activeNetwork: Network?,
        gatewayIp: String?,
        savedPortal: String?,
        ssid: String?
    ): String? {
        if (!savedPortal.isNullOrBlank()) {
            val savedHost = try {
                val uri = Uri.parse(if (savedPortal.startsWith("http://") || savedPortal.startsWith("https://")) savedPortal else "http://$savedPortal")
                uri.host
            } catch (_: Exception) {
                null
            }

            // Check if savedPortal is an old gateway IP from a previous Wi-Fi network
            val isIpv4 = savedHost != null && isIpv4Address(savedHost)
            val isStaleGateway = isIpv4 && !gatewayIp.isNullOrBlank() && savedHost != gatewayIp

            if (isStaleGateway) {
                Log.i(TAG, "Saved portal $savedPortal is from a previous Wi-Fi gateway ($savedHost vs current $gatewayIp). Clearing stale IP and auto-detecting for current network.")
                credentialStore.clearPortalUrl()
            } else {
                return savedPortal
            }
        }

        // Check if there is an SSID-specific cached portal
        if (!ssid.isNullOrBlank()) {
            val cachedForSsid = credentialStore.getPortalUrlForSsid(ssid)
            if (!cachedForSsid.isNullOrBlank()) {
                return cachedForSsid
            }
        }

        // Dynamic auto-detection for the current Wi-Fi network
        val discovered = connectivityChecker.detectCaptivePortalUrl(activeNetwork, gatewayIp)
        if (!discovered.isNullOrBlank()) {
            return discovered
        }

        return gatewayIp?.let { "http://$it:8090" } ?: "http://172.16.16.16:8090"
    }

    private fun isIpv4Address(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }

    /**
     * Checks current Wi-Fi and Internet status on app launch, resume, or network change.
     * Executes fast 0ms portal discovery and triggers auto-login if enabled.
     */
    fun refreshState() {
        viewModelScope.launch {
            val isWifi = wifiHelper.isWifiConnected()
            val hasCreds = credentialStore.hasCredentials()
            val savedUser = credentialStore.getSavedUsername()
            val isAutoLogin = credentialStore.isAutoLoginEnabled()

            if (!isWifi) {
                wifiHelper.unbindProcess()
                _uiState.update {
                    it.copy(
                        wifiConnected = false,
                        ssid = "No Wi-Fi",
                        wifiStatusText = "Disconnected",
                        internetStatusText = "No Wi-Fi connection",
                        internetState = InternetState.NO_INTERNET,
                        hasCredentials = hasCreds,
                        savedUsername = savedUser,
                        autoLoginEnabled = isAutoLogin,
                        statusMessage = "Please connect to Wi-Fi first.",
                        statusDetails = null,
                        statusType = StatusType.IDLE,
                        isLoading = false,
                        challengeUrl = null
                    )
                }
                return@launch
            }

            // Bind process to Wi-Fi to prevent Cellular / Mobile Data interference
            wifiHelper.bindProcessToWifi()

            val currentSsid = wifiHelper.getWifiSsid()
            val activeNetwork = wifiHelper.getActiveWifiNetwork()
            val gatewayIp = wifiHelper.getGatewayIp()
            val savedPortal = credentialStore.getSavedPortalUrl()

            // Step 1: Preliminary portal URL
            val preliminaryPortal = resolvePortalUrl(activeNetwork, gatewayIp, savedPortal, currentSsid)

            _uiState.update {
                it.copy(
                    wifiConnected = true,
                    ssid = currentSsid,
                    wifiStatusText = "Connected ✓",
                    portalUrl = preliminaryPortal,
                    challengeUrl = preliminaryPortal,
                    internetStatusText = "Checking...",
                    internetState = InternetState.CHECKING,
                    hasCredentials = hasCreds,
                    savedUsername = savedUser,
                    autoLoginEnabled = isAutoLogin,
                    isLoading = false
                )
            }

            // Step 2: Probe connectivity and simultaneously capture live redirect Location
            val checkResult = connectivityChecker.checkConnectivityAndPortal(activeNetwork, gatewayIp)
            val internet = checkResult.state
            val liveDiscoveredPortal = checkResult.portalUrl

            // If the live network firewall returned a portal redirect, that is ground truth!
            val finalPortal = liveDiscoveredPortal ?: preliminaryPortal

            if (!liveDiscoveredPortal.isNullOrBlank() && currentSsid.isNotBlank()) {
                credentialStore.savePortalUrlForSsid(currentSsid, liveDiscoveredPortal)
            }

            val (netText, statusText, statusType) = when {
                userExplicitlyLoggedOut && (internet == InternetState.AUTHENTICATION_REQUIRED || internet == InternetState.NO_INTERNET) -> {
                    Triple("Logged Out", "Logged out successfully ✓", StatusType.IDLE)
                }
                internet == InternetState.CONNECTED -> Triple("Connected ✓", "Internet connected ✓", StatusType.SUCCESS)
                internet == InternetState.AUTHENTICATION_REQUIRED -> Triple("Authentication Required", "Login required", StatusType.READY)
                internet == InternetState.NO_INTERNET -> Triple("No Internet", "Login required", StatusType.READY)
                else -> Triple("Checking...", "Checking...", StatusType.IDLE)
            }

            _uiState.update {
                it.copy(
                    internetStatusText = netText,
                    internetState = internet,
                    portalUrl = finalPortal,
                    statusMessage = statusText,
                    statusDetails = if (userExplicitlyLoggedOut && internet != InternetState.CONNECTED) "Session released. Tap CONNECT to reconnect." else null,
                    statusType = statusType,
                    challengeUrl = finalPortal
                )
            }

            // Step 3: Fast Auto-Login when enabled and authentication is needed!
            if (isAutoLogin && hasCreds && !_uiState.value.isLoading && !userExplicitlyLoggedOut &&
                (internet == InternetState.AUTHENTICATION_REQUIRED || internet == InternetState.NO_INTERNET)) {
                Log.i(TAG, "[AutoLogin] Auto-login triggered immediately upon Wi-Fi connection")
                onConnectClicked()
            }
        }
    }

    /**
     * Executes the rapid CONNECT flow.
     */
    fun onConnectClicked() {
        viewModelScope.launch {
            if (_uiState.value.isLoading) return@launch
            userExplicitlyLoggedOut = false

            // STEP 1: Check Wi-Fi
            if (!wifiHelper.isWifiConnected()) {
                _uiState.update {
                    it.copy(
                        statusMessage = "Please connect to Wi-Fi first.",
                        statusDetails = null,
                        statusType = StatusType.IDLE
                    )
                }
                return@launch
            }

            val activeNetwork = wifiHelper.getActiveWifiNetwork()

            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusMessage = "Logging in...",
                    statusDetails = null,
                    statusType = StatusType.CONNECTING
                )
            }

            // STEP 2: If internet is already active, finish immediately
            if (_uiState.value.internetState == InternetState.CONNECTED) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        internetStatusText = "Connected ✓",
                        statusMessage = "Internet already connected ✓",
                        statusDetails = null,
                        statusType = StatusType.SUCCESS
                    )
                }
                return@launch
            }

            // STEP 3: Retrieve saved credentials
            if (!credentialStore.hasCredentials()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Please save credentials first.",
                        statusDetails = null,
                        statusType = StatusType.READY,
                        openCredentialsDialogEvent = true
                    )
                }
                return@launch
            }

            val credentials = credentialStore.getCredentials()
            if (credentials == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Failed to load credentials from secure storage.",
                        statusDetails = null,
                        statusType = StatusType.ERROR_AUTH
                    )
                }
                return@launch
            }

            // STEP 4: Resolve portal endpoint dynamically for the current Wi-Fi network
            val currentSsid = wifiHelper.getWifiSsid()
            val savedPortal = credentialStore.getSavedPortalUrl()
            val gatewayIp = wifiHelper.getGatewayIp()
            val portalUrl = resolvePortalUrl(activeNetwork, gatewayIp, savedPortal, currentSsid)
                ?: _uiState.value.portalUrl

            // STEP 5: Fire captive portal credential login with candidate fallback list
            val candidateUrls = wifiHelper.getCandidatePortalUrls(portalUrl)
            val loginResult = captivePortalLogin.login(credentials, portalUrl, activeNetwork, candidateUrls)

            // Cache discovered working portal URL for this SSID
            val discoveredPortal = when (loginResult) {
                is LoginResult.Success -> loginResult.portalUrl
                is LoginResult.InvalidCredentials -> loginResult.portalUrl
                is LoginResult.LimitExceeded -> loginResult.portalUrl
                is LoginResult.ChallengeRequired -> loginResult.portalUrl
                is LoginResult.UnknownError -> loginResult.portalUrl
                else -> null
            }
            if (!discoveredPortal.isNullOrBlank()) {
                credentialStore.savePortalUrlForSsid(currentSsid, discoveredPortal)
                _uiState.update {
                    it.copy(portalUrl = discoveredPortal, challengeUrl = discoveredPortal)
                }
            }

            // STEP 6: Handle result instantly
            when (loginResult) {
                is LoginResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            internetStatusText = "Connected ✓",
                            statusMessage = "Connected ✓",
                            statusDetails = loginResult.message.takeIf { m -> m != "Connected ✓" },
                            statusType = StatusType.SUCCESS
                        )
                    }

                    // Verify active Internet in the background asynchronously
                    launch {
                        val postInternet = connectivityChecker.checkInternetConnectivity(activeNetwork)
                        if (postInternet == InternetState.CONNECTED) {
                            _uiState.update {
                                it.copy(internetStatusText = "Connected ✓")
                            }
                        }
                    }
                }

                is LoginResult.InvalidCredentials -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Invalid username or password.",
                            statusDetails = loginResult.message.takeIf { m -> m != "Invalid username or password." },
                            statusType = StatusType.ERROR_AUTH
                        )
                    }
                }

                is LoginResult.LimitExceeded -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Login limit exceeded.",
                            statusDetails = loginResult.details ?: loginResult.message,
                            statusType = StatusType.ERROR_LIMIT
                        )
                    }
                }

                is LoginResult.ChallengeRequired -> {
                    val targetUrl = loginResult.portalUrl ?: portalUrl ?: "http://neverssl.com"
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Additional authentication required.",
                            statusDetails = loginResult.message,
                            statusType = StatusType.CHALLENGE,
                            challengeUrl = targetUrl
                        )
                    }
                }

                is LoginResult.PortalUnavailable -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Authentication portal unavailable.",
                            statusDetails = "Could not reach login portal on $currentSsid. Tap 'Open Portal in Browser' to open manually.",
                            statusType = StatusType.ERROR_NETWORK,
                            challengeUrl = "http://neverssl.com"
                        )
                    }
                }

                is LoginResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Unable to connect to portal.",
                            statusDetails = loginResult.message.takeIf { m -> m != "Unable to connect to the authentication portal." },
                            statusType = StatusType.ERROR_NETWORK,
                            challengeUrl = "http://neverssl.com"
                        )
                    }
                }

                is LoginResult.UnknownError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Login failed",
                            statusDetails = loginResult.message.takeIf { m -> m != "Login failed" },
                            statusType = StatusType.ERROR_AUTH
                        )
                    }
                }
            }
        }
    }

    /**
     * Logs out of captive portal to release active session and avoid limit exceeded issues.
     */
    fun onLogoutClicked() {
        viewModelScope.launch {
            if (_uiState.value.isLoading) return@launch
            val username = credentialStore.getSavedUsername() ?: return@launch
            val activeNetwork = wifiHelper.getActiveWifiNetwork()
            val portalUrl = _uiState.value.portalUrl

            userExplicitlyLoggedOut = true

            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusMessage = "Logging out...",
                    statusDetails = null,
                    statusType = StatusType.CONNECTING
                )
            }

            val success = captivePortalLogin.logout(username, portalUrl, activeNetwork)
            Log.i(TAG, "[Logout] Portal session release completed (success: $success)")

            _uiState.update {
                it.copy(
                    isLoading = false,
                    internetState = InternetState.AUTHENTICATION_REQUIRED,
                    internetStatusText = "Logged Out",
                    statusMessage = "Logged out successfully ✓",
                    statusDetails = "Session released. Tap CONNECT to log back in.",
                    statusType = StatusType.IDLE
                )
            }
        }
    }

    /**
     * Toggles auto-login when connected to Wi-Fi.
     */
    fun setAutoLoginEnabled(enabled: Boolean) {
        credentialStore.setAutoLoginEnabled(enabled)
        _uiState.update { it.copy(autoLoginEnabled = enabled) }
        if (enabled && _uiState.value.wifiConnected && _uiState.value.hasCredentials && _uiState.value.internetState != InternetState.CONNECTED) {
            onConnectClicked()
        }
    }

    /**
     * Saves credentials into the Keystore-backed credential store.
     */
    fun saveCredentials(username: String, password: String, customPortalUrl: String?) {
        val currentSsid = wifiHelper.getWifiSsid()
        val success = credentialStore.saveCredentials(
            username = username,
            password = password,
            portalUrl = customPortalUrl?.takeIf { it.isNotBlank() },
            ssid = currentSsid
        )
        if (success) {
            viewModelScope.launch {
                refreshState()
            }
        }
    }

    fun getSavedCredentials(): UserCredentials? {
        return credentialStore.getCredentials()
    }

    fun clearSavedCredentials() {
        credentialStore.clearCredentials()
        _uiState.update {
            it.copy(
                hasCredentials = false,
                savedUsername = null,
                statusMessage = "Credentials cleared",
                statusDetails = null,
                statusType = StatusType.IDLE
            )
        }
    }

    fun consumeCredentialsDialogEvent() {
        _uiState.update { it.copy(openCredentialsDialogEvent = false) }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            val connectivityManager = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Unregistered Wi-Fi state listener")
        } catch (_: Exception) {}
    }
}
