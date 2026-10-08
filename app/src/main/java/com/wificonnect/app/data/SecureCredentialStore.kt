package com.wificonnect.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.wificonnect.app.model.UserCredentials
import com.wificonnect.app.model.WifiProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Lightweight hardware/Keystore-backed secure credential store and Wi-Fi profile repository.
 *
 * Stores credentials strictly locally on the Android device.
 * Passwords are encrypted using AES-256-GCM with keys managed by the Android Keystore.
 * Plaintext passwords are NEVER stored in SharedPreferences, SQLite, files, logs, or cloud.
 */
class SecureCredentialStore(private val context: Context) : CredentialRepository {

    companion object {
        private const val TAG = "SecureCredentialStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "WiFiConnectMasterKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        private const val PREFS_NAME = "wifi_connect_secure_prefs"
        private const val KEY_USERNAME = "pref_username"
        private const val KEY_ENCRYPTED_PASSWORD = "pref_enc_password"
        private const val KEY_PASSWORD_IV = "pref_password_iv"
        private const val KEY_PORTAL_URL = "pref_portal_url"
        private const val KEY_NETWORK_SSID = "pref_network_ssid"
        private const val KEY_AUTO_LOGIN = "pref_auto_login"

        // Profile keys prefix
        private const val PREFIX_PROFILE_URL = "profile_url_"
        private const val PREFIX_PROFILE_TYPE = "profile_type_"
        private const val PREFIX_PROFILE_LAST_SUCCESS = "profile_last_success_"
        private const val PREFIX_PROFILE_SUCCESS_COUNT = "profile_success_count_"
        private const val PREFIX_PROFILE_FAIL_COUNT = "profile_fail_count_"
        private const val PREFIX_PROFILE_AVG_TIME = "profile_avg_time_"
        private const val PREFIX_LEGACY_PORTAL_URL = "pref_portal_url_"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        ensureKeyExists()
    }

    /**
     * Ensures an AES-256 key exists in the Android Keystore under the alias.
     */
    private fun ensureKeyExists() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()

                keyGenerator.init(spec)
                keyGenerator.generateKey()
                Log.d(TAG, "Initialized new Android Keystore AES-256 key")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Keystore key", e)
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Keystore key not found")
    }

    /**
     * Encrypts plaintext password with AES-GCM and returns Pair(ciphertextBase64, ivBase64).
     */
    private fun encrypt(plainText: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Pair(
            Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
            Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    /**
     * Decrypts ciphertext with AES-GCM using the stored IV.
     */
    private fun decrypt(cipherTextBase64: String, ivBase64: String): String {
        val cipherBytes = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, Charsets.UTF_8)
    }

    /**
     * Saves user credentials.
     * The password is encrypted immediately with Android Keystore before writing to storage.
     */
    fun saveCredentials(
        username: String,
        password: String,
        portalUrl: String? = null,
        ssid: String? = null
    ): Boolean {
        return try {
            val (encryptedPassword, iv) = encrypt(password)
            val editor = prefs.edit()
                .putString(KEY_USERNAME, username.trim())
                .putString(KEY_ENCRYPTED_PASSWORD, encryptedPassword)
                .putString(KEY_PASSWORD_IV, iv)
                .putString(KEY_NETWORK_SSID, ssid?.trim())

            if (portalUrl.isNullOrBlank()) {
                editor.remove(KEY_PORTAL_URL)
            } else {
                editor.putString(KEY_PORTAL_URL, portalUrl.trim())
            }

            editor.apply()
            Log.i(TAG, "Credentials saved securely for user: ${username.trim()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving credentials", e)
            false
        }
    }

    // ==========================================
    // Wi-Fi Profile & Fast-Path Caching (Step 2 & 10)
    // ==========================================

    /**
     * Retrieves the persistent Wi-Fi profile for an SSID.
     * Automatically migrates legacy cached portal URLs if present.
     */
    override fun getWifiProfile(ssid: String): WifiProfile? {
        val cleanSsid = cleanSsid(ssid) ?: return null

        val portalUrl = prefs.getString("$PREFIX_PROFILE_URL$cleanSsid", null)
            ?: prefs.getString("$PREFIX_LEGACY_PORTAL_URL$cleanSsid", null)

        if (portalUrl.isNullOrBlank()) return null

        val portalType = prefs.getString("$PREFIX_PROFILE_TYPE$cleanSsid", "Cyberoam")
        val lastSuccess = prefs.getLong("$PREFIX_PROFILE_LAST_SUCCESS$cleanSsid", 0L)
        val successCount = prefs.getInt("$PREFIX_PROFILE_SUCCESS_COUNT$cleanSsid", if (lastSuccess > 0L) 1 else 0)
        val failureCount = prefs.getInt("$PREFIX_PROFILE_FAIL_COUNT$cleanSsid", 0)
        val avgTime = prefs.getLong("$PREFIX_PROFILE_AVG_TIME$cleanSsid", 0L)

        return WifiProfile(
            ssid = cleanSsid,
            portalUrl = portalUrl,
            portalType = portalType,
            lastSuccessfulLogin = lastSuccess,
            successCount = successCount,
            failureCount = failureCount,
            averageLoginTimeMs = avgTime
        )
    }

    /**
     * Saves or updates a persistent Wi-Fi profile for an SSID.
     */
    override fun saveWifiProfile(profile: WifiProfile) {
        val cleanSsid = cleanSsid(profile.ssid) ?: return
        val editor = prefs.edit()

        if (profile.portalUrl.isNullOrBlank()) {
            editor.remove("$PREFIX_PROFILE_URL$cleanSsid")
            editor.remove("$PREFIX_LEGACY_PORTAL_URL$cleanSsid")
        } else {
            editor.putString("$PREFIX_PROFILE_URL$cleanSsid", profile.portalUrl.trim())
            editor.putString("$PREFIX_LEGACY_PORTAL_URL$cleanSsid", profile.portalUrl.trim())
        }

        editor.putString("$PREFIX_PROFILE_TYPE$cleanSsid", profile.portalType ?: "Cyberoam")
        editor.putLong("$PREFIX_PROFILE_LAST_SUCCESS$cleanSsid", profile.lastSuccessfulLogin)
        editor.putInt("$PREFIX_PROFILE_SUCCESS_COUNT$cleanSsid", profile.successCount)
        editor.putInt("$PREFIX_PROFILE_FAIL_COUNT$cleanSsid", profile.failureCount)
        editor.putLong("$PREFIX_PROFILE_AVG_TIME$cleanSsid", profile.averageLoginTimeMs)
        editor.apply()

        Log.d(TAG, "Saved profile for '$cleanSsid': portal=${profile.portalUrl}, successes=${profile.successCount}, failures=${profile.failureCount}")
    }

    /**
     * Records a successful login for an SSID to train the fast path.
     */
    override fun recordLoginSuccess(ssid: String, portalUrl: String, durationMs: Long) {
        val cleanSsid = cleanSsid(ssid) ?: return
        val existing = getWifiProfile(cleanSsid)

        val newSuccessCount = (existing?.successCount ?: 0) + 1
        val newAvgTime = if ((existing?.averageLoginTimeMs ?: 0L) > 0L) {
            ((existing!!.averageLoginTimeMs * (newSuccessCount - 1)) + durationMs) / newSuccessCount
        } else {
            durationMs
        }

        val updated = WifiProfile(
            ssid = cleanSsid,
            portalUrl = portalUrl.trim(),
            portalType = existing?.portalType ?: "Cyberoam",
            lastSuccessfulLogin = System.currentTimeMillis(),
            successCount = newSuccessCount,
            failureCount = 0, // Reset consecutive failure counter on success
            averageLoginTimeMs = newAvgTime
        )

        saveWifiProfile(updated)
        Log.i(TAG, "Recorded fast-path success for '$cleanSsid' in ${durationMs}ms (avg: ${newAvgTime}ms)")
    }

    /**
     * Records a login failure for an SSID to track endpoint staleness.
     */
    override fun recordLoginFailure(ssid: String) {
        val cleanSsid = cleanSsid(ssid) ?: return
        val existing = getWifiProfile(cleanSsid) ?: return

        val newFailCount = existing.failureCount + 1
        val updated = existing.copy(failureCount = newFailCount)
        saveWifiProfile(updated)

        if (updated.isStale) {
            Log.w(TAG, "Profile for '$cleanSsid' reached $newFailCount consecutive failures; marked STALE for rediscovery.")
        } else {
            Log.d(TAG, "Recorded failure count $newFailCount for '$cleanSsid'")
        }
    }

    /**
     * Clears cached portal endpoint for an SSID.
     */
    override fun clearProfileForSsid(ssid: String) {
        val cleanSsid = cleanSsid(ssid) ?: return
        prefs.edit()
            .remove("$PREFIX_PROFILE_URL$cleanSsid")
            .remove("$PREFIX_PROFILE_TYPE$cleanSsid")
            .remove("$PREFIX_PROFILE_LAST_SUCCESS$cleanSsid")
            .remove("$PREFIX_PROFILE_SUCCESS_COUNT$cleanSsid")
            .remove("$PREFIX_PROFILE_FAIL_COUNT$cleanSsid")
            .remove("$PREFIX_PROFILE_AVG_TIME$cleanSsid")
            .remove("$PREFIX_LEGACY_PORTAL_URL$cleanSsid")
            .apply()
        Log.i(TAG, "Cleared profile cache for '$cleanSsid'")
    }

    /**
     * Clears all saved custom and cached portal URLs.
     */
    override fun clearPortalUrl() {
        val editor = prefs.edit().remove(KEY_PORTAL_URL)
        for (key in prefs.all.keys) {
            if (key.startsWith(PREFIX_LEGACY_PORTAL_URL) || key.startsWith("profile_")) {
                editor.remove(key)
            }
        }
        editor.apply()
        Log.i(TAG, "Cleared all cached portal profiles (auto-detect enabled)")
    }

    /**
     * Legacy helper: Retrieves cached working portal URL for a specific Wi-Fi SSID.
     */
    fun getPortalUrlForSsid(ssid: String): String? {
        return getWifiProfile(ssid)?.portalUrl
    }

    /**
     * Legacy helper: Caches working portal URL for a specific Wi-Fi SSID.
     */
    fun savePortalUrlForSsid(ssid: String, portalUrl: String) {
        val existing = getWifiProfile(ssid)
        val profile = existing?.copy(portalUrl = portalUrl.trim())
            ?: WifiProfile(ssid = ssid, portalUrl = portalUrl.trim(), successCount = 1)
        saveWifiProfile(profile)
    }

    private fun cleanSsid(ssid: String?): String? {
        if (ssid.isNullOrBlank() || ssid == "No Wi-Fi" || ssid == "Wi-Fi Network" || ssid == "<unknown ssid>") {
            return null
        }
        return ssid.trim().trim('"')
    }

    /**
     * Checks if credentials are saved.
     */
    override fun hasCredentials(): Boolean {
        val hasUser = !prefs.getString(KEY_USERNAME, null).isNullOrBlank()
        val hasPass = !prefs.getString(KEY_ENCRYPTED_PASSWORD, null).isNullOrBlank()
        return hasUser && hasPass
    }

    /**
     * Retrieves username without decrypting password.
     */
    override fun getSavedUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }

    /**
     * Retrieves saved portal URL if configured.
     */
    override fun getSavedPortalUrl(): String? {
        return prefs.getString(KEY_PORTAL_URL, null)
    }

    /**
     * Retrieves saved SSID profile if configured.
     */
    fun getSavedSsid(): String? {
        return prefs.getString(KEY_NETWORK_SSID, null)
    }

    /**
     * Retrieves and decrypts the credentials.
     * Retrieved ONLY during login or credential configuration.
     */
    override fun getCredentials(): UserCredentials? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val encPassword = prefs.getString(KEY_ENCRYPTED_PASSWORD, null) ?: return null
        val iv = prefs.getString(KEY_PASSWORD_IV, null) ?: return null
        val portalUrl = prefs.getString(KEY_PORTAL_URL, null)
        val ssid = prefs.getString(KEY_NETWORK_SSID, null)

        return try {
            val password = decrypt(encPassword, iv)
            UserCredentials(
                username = username,
                password = password,
                portalUrl = portalUrl,
                ssid = ssid
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt credentials", e)
            null
        }
    }

    /**
     * Checks if auto-login on Wi-Fi connection is enabled (defaults to true).
     */
    override fun isAutoLoginEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_LOGIN, true)
    }

    /**
     * Sets whether auto-login on Wi-Fi connection is enabled.
     */
    override fun setAutoLoginEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LOGIN, enabled).apply()
        Log.i(TAG, "Auto-login preference set to: $enabled")
    }

    /**
     * Clears all saved credentials.
     */
    override fun clearCredentials() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Stored credentials cleared")
    }
}
