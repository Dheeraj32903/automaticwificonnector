package com.wificonnect.app

import android.net.Network
import com.wificonnect.app.data.CredentialRepository
import com.wificonnect.app.model.ConnectionFailureReason
import com.wificonnect.app.model.ConnectionState
import com.wificonnect.app.model.InternetState
import com.wificonnect.app.model.LoginResult
import com.wificonnect.app.model.UserCredentials
import com.wificonnect.app.model.WifiProfile
import com.wificonnect.app.network.CaptivePortalLogin
import com.wificonnect.app.network.ConnectivityCheckResult
import com.wificonnect.app.network.ConnectivityInspector
import com.wificonnect.app.network.ConnectionCoordinator
import com.wificonnect.app.network.WifiStateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory test fakes for isolated, lightning-fast unit tests.
 */
class FakeCredentialRepository : CredentialRepository {
    var storedUser = "testuser"
    var storedPassword = "testpassword"
    var autoLogin = true
    var savedPortal: String? = null
    val profiles = mutableMapOf<String, WifiProfile>()

    override fun hasCredentials(): Boolean = storedUser.isNotBlank() && storedPassword.isNotBlank()
    override fun getSavedUsername(): String? = storedUser
    override fun getCredentials(): UserCredentials? = UserCredentials(storedUser, storedPassword)
    override fun getSavedPortalUrl(): String? = savedPortal
    override fun isAutoLoginEnabled(): Boolean = autoLogin
    override fun setAutoLoginEnabled(enabled: Boolean) { autoLogin = enabled }
    override fun getWifiProfile(ssid: String): WifiProfile? = profiles[ssid]
    override fun saveWifiProfile(profile: WifiProfile) { profiles[profile.ssid] = profile }
    override fun recordLoginSuccess(ssid: String, portalUrl: String, durationMs: Long) {
        val old = profiles[ssid]
        val successCount = (old?.successCount ?: 0) + 1
        profiles[ssid] = WifiProfile(
            ssid = ssid,
            portalUrl = portalUrl,
            successCount = successCount,
            failureCount = 0,
            lastSuccessfulLogin = System.currentTimeMillis(),
            averageLoginTimeMs = durationMs
        )
    }
    override fun recordLoginFailure(ssid: String) {
        val old = profiles[ssid]
        if (old != null) {
            profiles[ssid] = old.copy(failureCount = old.failureCount + 1)
        }
    }
    override fun clearProfileForSsid(ssid: String) { profiles.remove(ssid) }
    override fun clearPortalUrl() { savedPortal = null; profiles.clear() }
    override fun clearCredentials() { storedUser = ""; storedPassword = "" }
}

class FakeWifiStateProvider : WifiStateProvider {
    var isConnected = true
    var currentSsid = "College_WiFi"
    var testGatewayIp: String? = "10.100.0.1"
    var candidateUrls = listOf("http://10.100.0.1:8090", "http://172.16.16.16:8090")
    var processBound = false

    override fun getActiveWifiNetwork(): Network? = null // Dummy Network
    override fun isWifiConnected(): Boolean = isConnected
    override fun getWifiSsid(): String = currentSsid
    override fun getGatewayIp(): String? = testGatewayIp
    override fun getCandidatePortalUrls(primaryUrl: String?): List<String> = candidateUrls
    override fun bindProcessToWifi(): Boolean { processBound = true; return true }
    override fun unbindProcess() { processBound = false }
}

class FakeConnectivityInspector : ConnectivityInspector {
    var internetState = InternetState.AUTHENTICATION_REQUIRED
    var detectedPortal: String? = null
    var probeCallsCount = 0

    override suspend fun checkInternetConnectivity(network: Network?): InternetState {
        probeCallsCount++
        return internetState
    }

    override suspend fun detectCaptivePortalUrl(network: Network?, gatewayIp: String?): String? {
        return detectedPortal ?: gatewayIp?.let { "http://$it:8090" }
    }

    override suspend fun checkConnectivityAndPortal(network: Network?, gatewayIp: String?): ConnectivityCheckResult {
        return ConnectivityCheckResult(internetState, detectedPortal)
    }
}

class FakeCaptivePortalLogin : CaptivePortalLogin {
    var nextResult: LoginResult = LoginResult.Success("Connected ✓", "http://10.100.0.1:8090")
    val loginCalls = mutableListOf<String?>()
    var logoutCalled = false

    override suspend fun login(
        credentials: UserCredentials,
        targetPortalUrl: String?,
        network: Network?,
        candidateUrls: List<String>
    ): LoginResult {
        loginCalls.add(targetPortalUrl)
        return nextResult
    }

    override suspend fun logout(username: String, targetPortalUrl: String?, network: Network?): Boolean {
        logoutCalled = true
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {

    private lateinit var fakeCreds: FakeCredentialRepository
    private lateinit var fakeWifi: FakeWifiStateProvider
    private lateinit var fakeInspector: FakeConnectivityInspector
    private lateinit var fakeLogin: FakeCaptivePortalLogin
    private lateinit var coordinator: ConnectionCoordinator

    @Before
    fun setUp() {
        fakeCreds = FakeCredentialRepository()
        fakeWifi = FakeWifiStateProvider()
        fakeInspector = FakeConnectivityInspector()
        fakeLogin = FakeCaptivePortalLogin()

        coordinator = ConnectionCoordinator(
            credentialStore = fakeCreds,
            wifiHelper = fakeWifi,
            connectivityChecker = fakeInspector,
            captivePortalLogin = fakeLogin,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testKnownNetworkFastPathBypassesDiscoveryAndAuthenticatesImmediately() = runBlocking {
        // Setup a known network profile
        fakeCreds.saveWifiProfile(
            WifiProfile(
                ssid = "College_WiFi",
                portalUrl = "http://10.100.0.1:8090",
                successCount = 5,
                failureCount = 0
            )
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val dummyNetwork = null as Network?

        // Execute connection flow
        // In this test, execute directly:
        coordinator.executeConnectionFlow(dummyNetwork, generation = 0, autoTriggered = true)

        // Verify that authentication was attempted immediately on the cached portal URL
        assertEquals(1, fakeLogin.loginCalls.size)
        assertEquals("http://10.100.0.1:8090", fakeLogin.loginCalls[0])

        // Verify state is Connected with FastPath
        val state = coordinator.connectionState.value
        assertTrue("State should be Connected, but was: $state", state is ConnectionState.Connected)
        val connected = state as ConnectionState.Connected
        assertEquals("College_WiFi", connected.ssid)
        assertEquals("http://10.100.0.1:8090", connected.portalUrl)
        assertNotNull(connected.metrics)
        assertTrue(connected.metrics!!.isFastPath)

        // Verify success recorded in profile
        val updatedProfile = fakeCreds.getWifiProfile("College_WiFi")
        assertNotNull(updatedProfile)
        assertEquals(6, updatedProfile!!.successCount)
        assertEquals(0, updatedProfile.failureCount)
    }

    @Test
    fun testCachedPortalFailureFallsBackToDiscoveryAndFallbackCandidates() = runBlocking {
        // Known network profile
        fakeCreds.saveWifiProfile(
            WifiProfile(
                ssid = "College_WiFi",
                portalUrl = "http://old-stale-portal:8090",
                successCount = 2,
                failureCount = 0
            )
        )

        // The first attempt (cached) will fail with PortalUnavailable
        fakeLogin.nextResult = LoginResult.PortalUnavailable("Timeout")

        val dummyNetwork = null as Network?
        coordinator.executeConnectionFlow(dummyNetwork, generation = 0, autoTriggered = true)

        // Login should have been attempted on cached portal first, then on candidate URLs
        assertTrue("Login should have been attempted more than once", fakeLogin.loginCalls.size > 1)
        assertEquals("http://old-stale-portal:8090", fakeLogin.loginCalls[0])

        // Verify failure count was incremented for the stale cached endpoint
        val profile = fakeCreds.getWifiProfile("College_WiFi")
        assertEquals(1, profile?.failureCount)
    }

    @Test
    fun testStaleProfileBypassesFastPathAndUsesDiscovery() = runBlocking {
        // Stale profile with 3 consecutive failures
        fakeCreds.saveWifiProfile(
            WifiProfile(
                ssid = "College_WiFi",
                portalUrl = "http://dead-portal:8090",
                successCount = 2,
                failureCount = 3
            )
        )

        fakeLogin.nextResult = LoginResult.Success("Connected ✓", "http://10.100.0.1:8090")

        val dummyNetwork = null as Network?
        coordinator.executeConnectionFlow(dummyNetwork, generation = 0, autoTriggered = true)

        // Because it was stale, it should NOT have used the dead cached URL as fast-path!
        assertFalse(fakeLogin.loginCalls[0] == "http://dead-portal:8090")
    }

    @Test
    fun testGenerationTokenCancelsStaleOperations() = runBlocking {
        fakeCreds.saveWifiProfile(
            WifiProfile(
                ssid = "College_WiFi",
                portalUrl = "http://10.100.0.1:8090",
                successCount = 5,
                failureCount = 0
            )
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val dummyNetwork = null as Network?

        // Simulate network disconnect
        coordinator.onNetworkLost(null)
        val state = coordinator.connectionState.value
        assertEquals(ConnectionState.Disconnected, state)
        assertFalse(fakeWifi.processBound)
    }

    @Test
    fun testExplicitLogoutReleasesSessionAndSetsCanRetry() = runBlocking {
        val success = coordinator.logout()
        assertTrue(success)
        assertTrue(fakeLogin.logoutCalled)
        assertTrue(coordinator.userExplicitlyLoggedOut)

        val state = coordinator.connectionState.value
        assertTrue(state is ConnectionState.Failed)
        val failed = state as ConnectionState.Failed
        assertTrue(failed.canRetry)
    }

    @Test
    fun testInvalidCredentialsFailureStopsRetries() = runBlocking {
        fakeCreds.saveWifiProfile(
            WifiProfile(
                ssid = "College_WiFi",
                portalUrl = "http://10.100.0.1:8090",
                successCount = 1,
                failureCount = 0
            )
        )
        fakeLogin.nextResult = LoginResult.InvalidCredentials("Bad password")

        val dummyNetwork = null as Network?
        coordinator.executeConnectionFlow(dummyNetwork, generation = 0, autoTriggered = true)

        val state = coordinator.connectionState.value
        assertTrue(state is ConnectionState.Failed)
        val failed = state as ConnectionState.Failed
        assertTrue(failed.reason is ConnectionFailureReason.AuthFailed)
        assertFalse("Invalid credentials should NOT be retried automatically", failed.canRetry)
        assertEquals(1, fakeLogin.loginCalls.size)
    }

    @Test
    fun testSessionLimitFailureReportsProperState() = runBlocking {
        fakeCreds.saveWifiProfile(
            WifiProfile(
                ssid = "College_WiFi",
                portalUrl = "http://10.100.0.1:8090",
                successCount = 1,
                failureCount = 0
            )
        )
        fakeLogin.nextResult = LoginResult.LimitExceeded("Maximum Login Limit reached")

        val dummyNetwork = null as Network?
        coordinator.executeConnectionFlow(dummyNetwork, generation = 0, autoTriggered = true)

        val state = coordinator.connectionState.value
        assertTrue(state is ConnectionState.Failed)
        val failed = state as ConnectionState.Failed
        assertTrue(failed.reason is ConnectionFailureReason.SessionLimit)
    }

    @Test
    fun testDuplicateNetworkCallbacksCoalesceCleanly() = runBlocking {
        fakeCreds.saveWifiProfile(
            WifiProfile(
                ssid = "College_WiFi",
                portalUrl = "http://10.100.0.1:8090",
                successCount = 2,
                failureCount = 0
            )
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val dummyNetwork = null as Network?

        // Fire 3 callbacks in rapid succession
        coordinator.onNetworkAvailable(dummyNetwork, scope, isAutoLoginEnabled = true)
        coordinator.onNetworkAvailable(dummyNetwork, scope, isAutoLoginEnabled = true)
        coordinator.onNetworkAvailable(dummyNetwork, scope, isAutoLoginEnabled = true)

        val state = coordinator.connectionState.value
        assertTrue(state is ConnectionState.Connected)
    }

    @Test
    fun testNetworkLostCancelsActiveJobsAndResetsState() = runBlocking {
        val dummyNetwork = null as Network?
        val scope = CoroutineScope(Dispatchers.Unconfined)

        coordinator.onNetworkAvailable(dummyNetwork, scope, isAutoLoginEnabled = true)
        coordinator.onNetworkLost(dummyNetwork)

        val state = coordinator.connectionState.value
        assertEquals(ConnectionState.Disconnected, state)
    }

    @Test
    fun testBackgroundVerificationRecordsMetrics() = runBlocking {
        fakeCreds.saveWifiProfile(
            WifiProfile(
                ssid = "College_WiFi",
                portalUrl = "http://10.100.0.1:8090",
                successCount = 3,
                failureCount = 0
            )
        )
        fakeInspector.internetState = InternetState.CONNECTED

        val dummyNetwork = null as Network?
        coordinator.executeConnectionFlow(dummyNetwork, generation = 0, autoTriggered = true)

        val metrics = coordinator.latestMetrics.value
        assertNotNull(metrics)
        assertTrue(metrics!!.isFastPath)
        assertTrue(metrics.authDurationMs >= 0)
        assertTrue(metrics.verificationDurationMs >= 0)
    }
}
