package com.wificonnect.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.wificonnect.app.model.UserCredentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Lightweight hardware/Keystore-backed secure credential store.
 *
 * Stores credentials strictly locally on the Android device.
 * Passwords are encrypted using AES-256-GCM with keys managed by the Android Keystore.
 * Plaintext passwords are NEVER stored in SharedPreferences, SQLite, files, logs, or cloud.
 */
class SecureCredentialStore(private val context: Context) {

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

    /**
     * Clears any saved custom portal URL so dynamic auto-detection is used across all Wi-Fi networks.
     */
    fun clearPortalUrl() {
        val editor = prefs.edit().remove(KEY_PORTAL_URL)
        for (key in prefs.all.keys) {
            if (key.startsWith("pref_portal_url_")) {
                editor.remove(key)
            }
        }
        editor.apply()
        Log.i(TAG, "Cleared saved custom and cached portal URLs (auto-detect enabled)")
    }

    /**
     * Retrieves cached working portal URL for a specific Wi-Fi SSID.
     */
    fun getPortalUrlForSsid(ssid: String): String? {
        if (ssid.isBlank() || ssid == "No Wi-Fi" || ssid == "Wi-Fi Network") return null
        return prefs.getString("pref_portal_url_${ssid.trim()}", null)
    }

    /**
     * Caches working portal URL for a specific Wi-Fi SSID.
     */
    fun savePortalUrlForSsid(ssid: String, portalUrl: String) {
        if (ssid.isBlank() || ssid == "No Wi-Fi" || ssid == "Wi-Fi Network") return
        prefs.edit().putString("pref_portal_url_${ssid.trim()}", portalUrl.trim()).apply()
        Log.i(TAG, "Cached working portal $portalUrl for SSID $ssid")
    }

    /**
     * Checks if credentials are saved.
     */
    fun hasCredentials(): Boolean {
        val hasUser = !prefs.getString(KEY_USERNAME, null).isNullOrBlank()
        val hasPass = !prefs.getString(KEY_ENCRYPTED_PASSWORD, null).isNullOrBlank()
        return hasUser && hasPass
    }

    /**
     * Retrieves username without decrypting password.
     */
    fun getSavedUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }

    /**
     * Retrieves saved portal URL if configured.
     */
    fun getSavedPortalUrl(): String? {
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
     * Retrieved ONLY when the user presses CONNECT or opens edit dialog.
     */
    fun getCredentials(): UserCredentials? {
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
    fun isAutoLoginEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_LOGIN, true)
    }

    /**
     * Sets whether auto-login on Wi-Fi connection is enabled.
     */
    fun setAutoLoginEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LOGIN, enabled).apply()
        Log.i(TAG, "Auto-login preference set to: $enabled")
    }

    /**
     * Clears all saved credentials.
     */
    fun clearCredentials() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Stored credentials cleared")
    }
}
