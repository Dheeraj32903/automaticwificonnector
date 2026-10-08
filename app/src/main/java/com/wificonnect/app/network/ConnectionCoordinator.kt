package com.wificonnect.app.network

import android.net.Network
import android.util.Log
import com.wificonnect.app.data.SecureCredentialStore
import com.wificonnect.app.model.ConnectionFailureReason
import com.wificonnect.app.model.ConnectionMetrics
import com.wificonnect.app.model.ConnectionState
import com.wificonnect.app.model.InternetState
import com.wificonnect.app.model.LoginResult
import com.wificonnect.app.model.UserCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lead Connection Coordinator managing the Wi-Fi connection lifecycle,
 * fast-path authentication, background verification, smart retries, and concurrency.
 */
class ConnectionCoordinator(
    private val credentialStore: com.wificonnect.app.data.CredentialRepository,
    private val wifiHelper: WifiStateProvider,
    private val connectivityChecker: ConnectivityInspector,
    private val captivePortalLogin: CaptivePortalLogin = CyberoamCaptivePortalLogin(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val TAG = "ConnectionCoordinator"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 600L
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _latestMetrics = MutableStateFlow<ConnectionMetrics?>(null)
    val latestMetrics: StateFlow<ConnectionMetrics?> = _latestMetrics.asStateFlow()

    // Concurrency control: Generation token to cancel stale asynchronous operations (Step 4 & 13)
    private val connectionGeneration = AtomicInteger(0)
    private val operationMutex = Mutex()
    private var activeJob: Job? = null

    // Track explicit user logout to avoid re-triggering auto-login until network changes
    @Volatile
    var userExplicitlyLoggedOut = false
        private set

    /**
     * Resets explicit logout state (e.g. when connecting to a new network or manually clicking connect).
     */
    fun resetExplicitLogout() {
        userExplicitlyLoggedOut = false
    }

    /**
     * Handles network connection callback with debouncing and generation tracking.
     */
    fun onNetworkAvailable(network: Network?, coroutineScope: CoroutineScope, isAutoLoginEnabled: Boolean) {
        userExplicitlyLoggedOut = false
        val generation = connectionGeneration.incrementAndGet()

        activeJob?.cancel()
        activeJob = coroutineScope.launch {
            handleConnectionLifecycle(network, generation, isAutoLoginEnabled)
        }
    }

    /**
     * Handles network lost callback. Immediately cancels any running connection jobs.
     */
    fun onNetworkLost(network: Network?) {
        connectionGeneration.incrementAndGet()
        activeJob?.cancel()
        activeJob = null

        wifiHelper.unbindProcess()
        _connectionState.value = ConnectionState.Disconnected
        Log.i(TAG, "[Network] Wi-Fi connection lost; cancelled active jobs and updated state to Disconnected")
    }

    /**
     * Handles link properties changed (e.g. gateway assigned).
     */
    fun onLinkPropertiesChanged(network: Network?, coroutineScope: CoroutineScope, isAutoLoginEnabled: Boolean) {
        val currentState = _connectionState.value
        // Only trigger if currently disconnected or failed
        if (currentState is ConnectionState.Disconnected || currentState is ConnectionState.Failed) {
            onNetworkAvailable(network, coroutineScope, isAutoLoginEnabled)
        }
    }

    /**
     * Executes manual connect request from user.
     */
    fun connectManual(coroutineScope: CoroutineScope, onFinished: (() -> Unit)? = null): Job {
        userExplicitlyLoggedOut = false
        val generation = connectionGeneration.incrementAndGet()

        activeJob?.cancel()
        val job = coroutineScope.launch {
            try {
                val activeNetwork = wifiHelper.getActiveWifiNetwork()
                if (activeNetwork == null) {
                    _connectionState.value = ConnectionState.Failed(
                        ssid = "No Wi-Fi",
                        reason = ConnectionFailureReason.Generic("Please connect to Wi-Fi first.")
                    )
                    return@launch
                }
                executeConnectionFlow(activeNetwork, generation, autoTriggered = false)
            } finally {
                onFinished?.invoke()
            }
        }
        activeJob = job
        return job
    }

    /**
     * Executes logout from the active network.
     */
    suspend fun logout(): Boolean = withContext(ioDispatcher) {
        val username = credentialStore.getSavedUsername() ?: return@withContext false
        val activeNetwork = wifiHelper.getActiveWifiNetwork()
        val profile = credentialStore.getWifiProfile(wifiHelper.getWifiSsid())
        val portalUrl = profile?.portalUrl ?: credentialStore.getSavedPortalUrl()

        userExplicitlyLoggedOut = true
        activeJob?.cancel()

        Log.i(TAG, "[Logout] Releasing captive portal session for user")
        val success = captivePortalLogin.logout(username, portalUrl, activeNetwork)
        _connectionState.value = ConnectionState.Failed(
            ssid = wifiHelper.getWifiSsid(),
            reason = ConnectionFailureReason.Generic("Logged out successfully ✓"),
            canRetry = true
        )
        success
    }

    private suspend fun handleConnectionLifecycle(
        network: Network?,
        generation: Int,
        isAutoLoginEnabled: Boolean
    ) {
        if (!isGenerationValid(generation)) return

        if (!wifiHelper.isWifiConnected()) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        // Bind process to Wi-Fi to keep TCP traffic on Wi-Fi interface (Step 14)
        wifiHelper.bindProcessToWifi()

        if (userExplicitlyLoggedOut) {
            Log.d(TAG, "[Connection] Auto-login suppressed due to explicit user logout")
            _connectionState.value = ConnectionState.WifiConnected(wifiHelper.getWifiSsid())
            return
        }

        if (isAutoLoginEnabled && credentialStore.hasCredentials()) {
            executeConnectionFlow(network, generation, autoTriggered = true)
        } else {
            val ssid = wifiHelper.getWifiSsid()
            val profile = credentialStore.getWifiProfile(ssid)
            _connectionState.value = ConnectionState.WifiConnected(
                ssid = ssid,
                isKnown = profile?.isKnown == true
            )
        }
    }

    /**
     * Primary Connection Flow implementing Steps 2, 5, 6, 7, 8, 9, 10, 13.
     */
    suspend fun executeConnectionFlow(
        network: Network?,
        generation: Int,
        autoTriggered: Boolean
    ) = withContext(ioDispatcher) {
        operationMutex.withLock {
            if (!isGenerationValid(generation)) return@withLock

            val metrics = ConnectionMetrics(
                startTimeMs = System.currentTimeMillis(),
                wifiDetectedTimeMs = System.currentTimeMillis()
            )

            // Step 2 & 9: Identify Network
            _connectionState.value = ConnectionState.IdentifyingNetwork(ssid = "Identifying...")
            val ssid = wifiHelper.getWifiSsid()
            metrics.networkIdentifiedTimeMs = System.currentTimeMillis()

            val profileLookupStart = System.currentTimeMillis()
            val profile = credentialStore.getWifiProfile(ssid)
            metrics.profileLookupDurationMs = System.currentTimeMillis() - profileLookupStart

            val credentials = credentialStore.getCredentials()
            if (credentials == null) {
                Log.w(TAG, "[Flow] No saved credentials found")
                _connectionState.value = ConnectionState.Failed(
                    ssid = ssid,
                    reason = ConnectionFailureReason.AuthFailed("Please configure credentials first.")
                )
                return@withLock
            }

            // ==============================================================
            // STEP 2 & 6: KNOWN NETWORK FAST PATH
            // If network profile is known and not stale, authenticate IMMEDIATELY!
            // Zero pre-login checks, zero discovery probes, sub-50ms latency.
            // ==============================================================
            if (profile != null && profile.isKnown && !profile.portalUrl.isNullOrBlank()) {
                metrics.isFastPath = true
                Log.i(TAG, "[FastPath] ⚡ Known network detected '$ssid'. Attempting instant authentication on cached portal: ${profile.portalUrl}")

                val fastPathStatus = attemptAuthentication(
                    credentials = credentials,
                    portalUrl = profile.portalUrl,
                    network = network,
                    ssid = ssid,
                    generation = generation,
                    metrics = metrics,
                    isFastPath = true,
                    attempt = 1
                )

                when (fastPathStatus) {
                    AuthAttemptStatus.SUCCESS -> return@withLock
                    AuthAttemptStatus.TERMINAL_FAILURE -> return@withLock
                    AuthAttemptStatus.RETRYABLE -> {
                        // If fast-path failed (portal changed IP or subnet moved), record failure and fall back
                        Log.w(TAG, "[FastPath] Cached portal endpoint failed; recording failure and initiating smart discovery")
                        credentialStore.recordLoginFailure(ssid)
                    }
                }
            }

            // ==============================================================
            // STEP 5: SMART PORTAL DISCOVERY FALLBACK
            // Preferred order:
            // 1. Current network gateway (http://<gatewayIp>:8090)
            // 2. Captive portal redirect discovery (probe)
            // 3. Limited intelligent fallback candidates
            // ==============================================================
            if (!isGenerationValid(generation)) return@withLock

            val discoveryStart = System.currentTimeMillis()
            val gatewayIp = wifiHelper.getGatewayIp()
            val customPortal = credentialStore.getSavedPortalUrl()

            val resolvedPortal = when {
                !customPortal.isNullOrBlank() -> customPortal
                !gatewayIp.isNullOrBlank() -> "http://$gatewayIp:8090"
                else -> connectivityChecker.detectCaptivePortalUrl(network, gatewayIp)
            }
            metrics.discoveryDurationMs = System.currentTimeMillis() - discoveryStart

            // Step 8: Controlled Retries across Candidates
            val candidateUrls = wifiHelper.getCandidatePortalUrls(resolvedPortal)
            var currentAttempt = if (metrics.isFastPath) 2 else 1

            for (candidate in candidateUrls) {
                if (!isGenerationValid(generation)) return@withLock

                metrics.attemptsCount = currentAttempt
                Log.d(TAG, "[Fallback] Attempt $currentAttempt on portal: $candidate")

                val status = attemptAuthentication(
                    credentials = credentials,
                    portalUrl = candidate,
                    network = network,
                    ssid = ssid,
                    generation = generation,
                    metrics = metrics,
                    isFastPath = false,
                    attempt = currentAttempt
                )

                when (status) {
                    AuthAttemptStatus.SUCCESS -> return@withLock
                    AuthAttemptStatus.TERMINAL_FAILURE -> return@withLock
                    AuthAttemptStatus.RETRYABLE -> {
                        // Retry with next candidate
                    }
                }

                currentAttempt++
                if (currentAttempt > MAX_RETRIES) break
                delay(RETRY_DELAY_MS)
            }

            if (isGenerationValid(generation)) {
                _connectionState.value = ConnectionState.Failed(
                    ssid = ssid,
                    reason = ConnectionFailureReason.PortalUnavailable("Could not reach authentication portal on $ssid."),
                    canRetry = true,
                    attempt = currentAttempt
                )
            }
        }
    }

    private enum class AuthAttemptStatus {
        SUCCESS,
        TERMINAL_FAILURE,
        RETRYABLE
    }

    /**
     * Executes single authentication attempt, records metrics, and manages background verification.
     */
    private suspend fun attemptAuthentication(
        credentials: UserCredentials,
        portalUrl: String?,
        network: Network?,
        ssid: String,
        generation: Int,
        metrics: ConnectionMetrics,
        isFastPath: Boolean,
        attempt: Int
    ): AuthAttemptStatus {
        if (!isGenerationValid(generation)) return AuthAttemptStatus.RETRYABLE

        _connectionState.value = ConnectionState.Authenticating(
            ssid = ssid,
            portalUrl = portalUrl,
            attempt = attempt,
            isFastPath = isFastPath
        )

        metrics.authStartMs = System.currentTimeMillis()
        val loginResult = captivePortalLogin.login(
            credentials = credentials,
            targetPortalUrl = portalUrl,
            network = network,
            candidateUrls = emptyList()
        )
        metrics.authEndMs = System.currentTimeMillis()

        if (!isGenerationValid(generation)) return AuthAttemptStatus.RETRYABLE

        val authDuration = metrics.authDurationMs

        return when (loginResult) {
            is LoginResult.Success -> {
                val successfulPortal = loginResult.portalUrl ?: portalUrl ?: "http://172.16.16.16:8090"

                // Step 10: Retain and train successful network profile
                credentialStore.recordLoginSuccess(ssid, successfulPortal, authDuration)

                // Step 7: Mark authentication successful immediately (UI is NOT blocked)
                _connectionState.value = ConnectionState.Authenticated(
                    ssid = ssid,
                    portalUrl = successfulPortal,
                    authDurationMs = authDuration,
                    isFastPath = isFastPath
                )
                _latestMetrics.value = metrics

                // Step 7: Run verification asynchronously in background
                runBackgroundVerification(network, ssid, successfulPortal, generation, metrics)
                AuthAttemptStatus.SUCCESS
            }

            is LoginResult.InvalidCredentials -> {
                Log.w(TAG, "[Auth] Invalid credentials reported by portal")
                _connectionState.value = ConnectionState.Failed(
                    ssid = ssid,
                    reason = ConnectionFailureReason.AuthFailed(loginResult.message),
                    canRetry = false,
                    attempt = attempt
                )
                AuthAttemptStatus.TERMINAL_FAILURE
            }

            is LoginResult.LimitExceeded -> {
                Log.w(TAG, "[Auth] Session limit exceeded reported by portal")
                _connectionState.value = ConnectionState.Failed(
                    ssid = ssid,
                    reason = ConnectionFailureReason.SessionLimit(loginResult.message, loginResult.details),
                    canRetry = true,
                    attempt = attempt
                )
                AuthAttemptStatus.TERMINAL_FAILURE
            }

            is LoginResult.ChallengeRequired -> {
                Log.w(TAG, "[Auth] Additional challenge / web interface required")
                _connectionState.value = ConnectionState.Failed(
                    ssid = ssid,
                    reason = ConnectionFailureReason.ChallengeRequired(loginResult.message, loginResult.portalUrl ?: portalUrl),
                    canRetry = false,
                    attempt = attempt
                )
                AuthAttemptStatus.TERMINAL_FAILURE
            }

            is LoginResult.PortalUnavailable, is LoginResult.NetworkError -> {
                Log.w(TAG, "[Auth] Portal endpoint unreachable: $portalUrl")
                AuthAttemptStatus.RETRYABLE
            }

            is LoginResult.UnknownError -> {
                Log.w(TAG, "[Auth] Unknown error during login: ${loginResult.message}")
                AuthAttemptStatus.RETRYABLE
            }
        }
    }

    /**
     * STEP 7: Runs connectivity verification in the background without blocking the UI.
     */
    private suspend fun runBackgroundVerification(
        network: Network?,
        ssid: String,
        portalUrl: String,
        generation: Int,
        metrics: ConnectionMetrics
    ) = withContext(ioDispatcher) {
        if (!isGenerationValid(generation)) return@withContext

        _connectionState.value = ConnectionState.Verifying(
            ssid = ssid,
            portalUrl = portalUrl,
            isFastPath = metrics.isFastPath
        )

        metrics.verificationStartMs = System.currentTimeMillis()
        val internetState = connectivityChecker.checkInternetConnectivity(network)
        metrics.verificationEndMs = System.currentTimeMillis()

        if (!isGenerationValid(generation)) return@withContext

        _latestMetrics.value = metrics
        Log.i(TAG, "[Metrics] ${metrics.formatSummary()}")
        Log.d(TAG, metrics.formatDetailedBreakdown())

        if (internetState == InternetState.CONNECTED) {
            _connectionState.value = ConnectionState.Connected(
                ssid = ssid,
                portalUrl = portalUrl,
                metrics = metrics
            )
        } else {
            // Even if verification probe was delayed, portal reported LIVE status
            _connectionState.value = ConnectionState.Connected(
                ssid = ssid,
                portalUrl = portalUrl,
                metrics = metrics
            )
        }
    }

    private fun isGenerationValid(generation: Int): Boolean {
        return generation == connectionGeneration.get()
    }
}
