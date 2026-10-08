package com.wificonnect.app.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wificonnect.app.data.SecureCredentialStore
import com.wificonnect.app.model.ConnectionFailureReason
import com.wificonnect.app.model.ConnectionMetrics
import com.wificonnect.app.model.ConnectionState
import com.wificonnect.app.model.InternetState
import com.wificonnect.app.model.UserCredentials
import com.wificonnect.app.network.ConnectivityChecker
import com.wificonnect.app.network.ConnectionCoordinator
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
    val openCredentialsDialogEvent: Boolean = false,
    val metrics: ConnectionMetrics? = null,
    val isFastPath: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val credentialStore = SecureCredentialStore(application)
    private val wifiHelper = WifiManagerHelper(application)
    private val connectivityChecker = ConnectivityChecker(application)
    private val captivePortalLogin = CyberoamCaptivePortalLogin()

    val connectionCoordinator = ConnectionCoordinator(
        credentialStore = credentialStore,
        wifiHelper = wifiHelper,
        connectivityChecker = connectivityChecker,
        captivePortalLogin = captivePortalLogin
    )

    private val _uiState = MutableStateFlow(
        MainUiState(
            hasCredentials = credentialStore.hasCredentials(),
            savedUsername = credentialStore.getSavedUsername(),
            autoLoginEnabled = credentialStore.isAutoLoginEnabled()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "[NetworkCallback] Wi-Fi network available")
            connectionCoordinator.onNetworkAvailable(
                network = network,
                coroutineScope = viewModelScope,
                isAutoLoginEnabled = credentialStore.isAutoLoginEnabled()
            )
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "[NetworkCallback] Wi-Fi network lost")
            connectionCoordinator.onNetworkLost(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // Android triggers capabilities changed on validation or transport updates
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                _uiState.update {
                    it.copy(
                        internetState = InternetState.CONNECTED,
                        internetStatusText = "Connected ✓"
                    )
                }
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            Log.d(TAG, "[NetworkCallback] Link properties changed (gateway/DNS assigned)")
            connectionCoordinator.onLinkPropertiesChanged(
                network = network,
                coroutineScope = viewModelScope,
                isAutoLoginEnabled = credentialStore.isAutoLoginEnabled()
            )
        }
    }

    init {
        registerNetworkCallback()
        observeConnectionCoordinator()
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

    private fun observeConnectionCoordinator() {
        viewModelScope.launch {
            connectionCoordinator.connectionState.collect { state ->
                applyConnectionState(state)
            }
        }

        viewModelScope.launch {
            connectionCoordinator.latestMetrics.collect { metrics ->
                if (metrics != null) {
                    _uiState.update { it.copy(metrics = metrics) }
                }
            }
        }
    }

    private fun applyConnectionState(state: ConnectionState) {
        val hasCreds = credentialStore.hasCredentials()
        val savedUser = credentialStore.getSavedUsername()
        val autoLogin = credentialStore.isAutoLoginEnabled()

        when (state) {
            is ConnectionState.Disconnected -> {
                _uiState.update {
                    it.copy(
                        wifiConnected = false,
                        ssid = "No Wi-Fi",
                        wifiStatusText = "Disconnected",
                        internetStatusText = "No Wi-Fi connection",
                        internetState = InternetState.NO_INTERNET,
                        hasCredentials = hasCreds,
                        savedUsername = savedUser,
                        autoLoginEnabled = autoLogin,
                        statusMessage = "Please connect to Wi-Fi first.",
                        statusDetails = null,
                        statusType = StatusType.IDLE,
                        isLoading = false,
                        challengeUrl = null
                    )
                }
            }

            is ConnectionState.WifiConnected -> {
                val profile = credentialStore.getWifiProfile(state.ssid)
                val isKnown = state.isKnown || (profile?.isKnown == true)
                _uiState.update {
                    it.copy(
                        wifiConnected = true,
                        ssid = state.ssid,
                        wifiStatusText = "Connected ✓",
                        portalUrl = profile?.portalUrl,
                        internetStatusText = "Ready to connect",
                        internetState = InternetState.AUTHENTICATION_REQUIRED,
                        hasCredentials = hasCreds,
                        savedUsername = savedUser,
                        autoLoginEnabled = autoLogin,
                        statusMessage = if (isKnown) "⚡ Known Wi-Fi: ${state.ssid}" else "Connected to ${state.ssid}",
                        statusDetails = if (connectionCoordinator.userExplicitlyLoggedOut) "Session released. Tap CONNECT to log back in." else null,
                        statusType = StatusType.READY,
                        isLoading = false,
                        isFastPath = isKnown
                    )
                }
            }

            is ConnectionState.IdentifyingNetwork -> {
                _uiState.update {
                    it.copy(
                        wifiConnected = true,
                        ssid = state.ssid,
                        wifiStatusText = "Connected ✓",
                        statusMessage = "Identifying network...",
                        statusDetails = null,
                        statusType = StatusType.CONNECTING,
                        isLoading = true
                    )
                }
            }

            is ConnectionState.Authenticating -> {
                val msg = if (state.isFastPath) {
                    "⚡ Authenticating (Fast-Path)..."
                } else {
                    "🔐 Authenticating (Attempt ${state.attempt})..."
                }
                _uiState.update {
                    it.copy(
                        wifiConnected = true,
                        ssid = state.ssid,
                        wifiStatusText = "Connected ✓",
                        portalUrl = state.portalUrl,
                        statusMessage = msg,
                        statusDetails = state.portalUrl?.let { url -> "Endpoint: $url" },
                        statusType = StatusType.CONNECTING,
                        isLoading = true,
                        isFastPath = state.isFastPath
                    )
                }
            }

            is ConnectionState.Authenticated -> {
                val details = if (state.isFastPath) {
                    "⚡ Fast-Path connected in ${state.authDurationMs}ms"
                } else {
                    "Authenticated in ${state.authDurationMs}ms"
                }
                _uiState.update {
                    it.copy(
                        wifiConnected = true,
                        ssid = state.ssid,
                        wifiStatusText = "Connected ✓",
                        internetStatusText = "Connected ✓",
                        internetState = InternetState.CONNECTED,
                        portalUrl = state.portalUrl,
                        statusMessage = "Connected ✓",
                        statusDetails = details,
                        statusType = StatusType.SUCCESS,
                        isLoading = false,
                        isFastPath = state.isFastPath
                    )
                }
            }

            is ConnectionState.Verifying -> {
                _uiState.update {
                    it.copy(
                        wifiConnected = true,
                        ssid = state.ssid,
                        wifiStatusText = "Connected ✓",
                        internetStatusText = "Connected ✓",
                        internetState = InternetState.CONNECTED,
                        portalUrl = state.portalUrl,
                        statusMessage = "Connected ✓",
                        statusDetails = "Verifying connection in background...",
                        statusType = StatusType.SUCCESS,
                        isLoading = false,
                        isFastPath = state.isFastPath
                    )
                }
            }

            is ConnectionState.Connected -> {
                val details = state.metrics?.formatSummary() ?: "Internet connected ✓"
                _uiState.update {
                    it.copy(
                        wifiConnected = true,
                        ssid = state.ssid,
                        wifiStatusText = "Connected ✓",
                        internetStatusText = "Connected ✓",
                        internetState = InternetState.CONNECTED,
                        portalUrl = state.portalUrl,
                        statusMessage = "Connected ✓",
                        statusDetails = details,
                        statusType = StatusType.SUCCESS,
                        isLoading = false,
                        metrics = state.metrics,
                        isFastPath = state.metrics?.isFastPath == true
                    )
                }
            }

            is ConnectionState.Failed -> {
                handleFailureState(state)
            }
        }
    }

    private fun handleFailureState(state: ConnectionState.Failed) {
        val (statusText, details, statusType, challenge) = when (val reason = state.reason) {
            is ConnectionFailureReason.AuthFailed -> {
                Tuple4("Invalid username or password.", reason.message, StatusType.ERROR_AUTH, null)
            }
            is ConnectionFailureReason.SessionLimit -> {
                Tuple4("Login limit exceeded.", reason.details ?: reason.message, StatusType.ERROR_LIMIT, null)
            }
            is ConnectionFailureReason.ChallengeRequired -> {
                Tuple4("Additional authentication required.", reason.message, StatusType.CHALLENGE, reason.challengeUrl)
            }
            is ConnectionFailureReason.PortalUnavailable -> {
                Tuple4("Authentication portal unavailable.", reason.message, StatusType.ERROR_NETWORK, "http://neverssl.com")
            }
            is ConnectionFailureReason.NetworkChanged -> {
                Tuple4("Network changed", "Wi-Fi connection changed.", StatusType.IDLE, null)
            }
            is ConnectionFailureReason.DiscoveryFailed -> {
                Tuple4("Portal discovery failed.", reason.message, StatusType.ERROR_NETWORK, "http://neverssl.com")
            }
            is ConnectionFailureReason.VerificationFailed -> {
                Tuple4("Verification failed.", reason.message, StatusType.ERROR_NETWORK, null)
            }
            is ConnectionFailureReason.Generic -> {
                Tuple4(reason.message, null, StatusType.IDLE, null)
            }
        }

        _uiState.update {
            it.copy(
                wifiConnected = wifiHelper.isWifiConnected(),
                ssid = state.ssid,
                statusMessage = statusText,
                statusDetails = details,
                statusType = statusType,
                isLoading = false,
                challengeUrl = challenge
            )
        }
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /**
     * Checks current Wi-Fi status on resume or refresh button click.
     */
    fun refreshState() {
        val isWifi = wifiHelper.isWifiConnected()
        val hasCreds = credentialStore.hasCredentials()
        val savedUser = credentialStore.getSavedUsername()
        val autoLogin = credentialStore.isAutoLoginEnabled()

        if (!isWifi) {
            connectionCoordinator.onNetworkLost(null)
            return
        }

        val network = wifiHelper.getActiveWifiNetwork()
        if (network != null) {
            connectionCoordinator.onNetworkAvailable(
                network = network,
                coroutineScope = viewModelScope,
                isAutoLoginEnabled = autoLogin
            )
        } else {
            val ssid = wifiHelper.getWifiSsid()
            _uiState.update {
                it.copy(
                    wifiConnected = true,
                    ssid = ssid,
                    wifiStatusText = "Connected ✓",
                    hasCredentials = hasCreds,
                    savedUsername = savedUser,
                    autoLoginEnabled = autoLogin,
                    statusMessage = "Connected to $ssid",
                    statusType = StatusType.READY,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Executes manual connect.
     */
    fun onConnectClicked() {
        if (_uiState.value.isLoading) return

        if (!wifiHelper.isWifiConnected()) {
            _uiState.update {
                it.copy(
                    statusMessage = "Please connect to Wi-Fi first.",
                    statusDetails = null,
                    statusType = StatusType.IDLE
                )
            }
            return
        }

        if (!credentialStore.hasCredentials()) {
            _uiState.update {
                it.copy(
                    statusMessage = "Please save credentials first.",
                    statusDetails = null,
                    statusType = StatusType.READY,
                    openCredentialsDialogEvent = true
                )
            }
            return
        }

        connectionCoordinator.connectManual(viewModelScope)
    }

    /**
     * Executes logout.
     */
    fun onLogoutClicked() {
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusMessage = "Logging out...",
                    statusDetails = null,
                    statusType = StatusType.CONNECTING
                )
            }

            connectionCoordinator.logout()

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
     * Saves credentials securely.
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
            _uiState.update {
                it.copy(
                    hasCredentials = true,
                    savedUsername = username.trim()
                )
            }
            refreshState()
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
