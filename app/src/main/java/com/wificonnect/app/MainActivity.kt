package com.wificonnect.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wificonnect.app.databinding.ActivityMainBinding
import com.wificonnect.app.databinding.DialogCredentialsBinding
import com.wificonnect.app.model.InternetState
import com.wificonnect.app.viewmodel.MainUiState
import com.wificonnect.app.viewmodel.MainViewModel
import com.wificonnect.app.viewmodel.StatusType
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // Optional location permission for reading exact Wi-Fi SSID on Android 9+
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            viewModel.refreshState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        observeViewModel()
        checkLocationPermissionForSsid()
    }

    override fun onResume() {
        super.onResume()
        // Re-check network conditions whenever the user brings the app to the foreground
        viewModel.refreshState()
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            if (viewModel.uiState.value.internetState == InternetState.CONNECTED) {
                viewModel.onLogoutClicked()
            } else {
                viewModel.onConnectClicked()
            }
        }

        binding.switchAutoLogin.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoLoginEnabled(isChecked)
        }

        binding.btnEditCredentials.setOnClickListener {
            showCredentialsDialog()
        }

        binding.btnRefresh.setOnClickListener {
            viewModel.refreshState()
        }

        binding.btnOpenChallenge.setOnClickListener {
            val url = viewModel.uiState.value.challengeUrl
                ?: viewModel.uiState.value.portalUrl
                ?: "http://neverssl.com"
            openPortalInBrowser(url)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUi(state)

                    if (state.openCredentialsDialogEvent) {
                        viewModel.consumeCredentialsDialogEvent()
                        showCredentialsDialog()
                    }
                }
            }
        }
    }

    private fun renderUi(state: MainUiState) {
        // SSID Display
        binding.tvCurrentSsid.text = state.ssid

        // Wi-Fi Status
        binding.tvWifiStatus.text = state.wifiStatusText
        val wifiColor = if (state.wifiConnected) {
            ContextCompat.getColor(this, R.color.status_green)
        } else {
            ContextCompat.getColor(this, R.color.status_red)
        }
        binding.tvWifiStatus.setTextColor(wifiColor)

        // Internet Status
        binding.tvInternetStatus.text = state.internetStatusText
        val internetColor = when (state.internetState) {
            InternetState.CONNECTED -> ContextCompat.getColor(this, R.color.status_green)
            InternetState.AUTHENTICATION_REQUIRED -> ContextCompat.getColor(this, R.color.status_orange)
            InternetState.NO_INTERNET -> ContextCompat.getColor(this, R.color.status_red)
            InternetState.CHECKING -> ContextCompat.getColor(this, R.color.status_neutral)
        }
        binding.tvInternetStatus.setTextColor(internetColor)

        // Portal URL row
        if (!state.portalUrl.isNullOrBlank()) {
            binding.layoutPortalUrl.visibility = View.VISIBLE
            binding.tvPortalUrl.text = state.portalUrl
        } else {
            binding.layoutPortalUrl.visibility = View.GONE
        }

        // Saved account
        if (state.hasCredentials && !state.savedUsername.isNullOrBlank()) {
            binding.tvSavedAccount.text = state.savedUsername
            binding.btnEditCredentials.text = getString(R.string.edit_credentials)
        } else {
            binding.tvSavedAccount.text = getString(R.string.setup_credentials)
            binding.btnEditCredentials.text = getString(R.string.setup_credentials)
        }

        // Status Card Styling & Message
        binding.tvStatusMessage.text = state.statusMessage

        if (!state.statusDetails.isNullOrBlank() && state.statusDetails != state.statusMessage) {
            binding.tvStatusDetails.visibility = View.VISIBLE
            binding.tvStatusDetails.text = state.statusDetails
        } else {
            binding.tvStatusDetails.visibility = View.GONE
        }

        val (statusBgColor, statusTextColor) = when (state.statusType) {
            StatusType.SUCCESS -> Pair(
                ContextCompat.getColor(this, R.color.status_green_bg),
                ContextCompat.getColor(this, R.color.status_green)
            )
            StatusType.ERROR_AUTH -> Pair(
                ContextCompat.getColor(this, R.color.status_red_bg),
                ContextCompat.getColor(this, R.color.status_red)
            )
            StatusType.ERROR_LIMIT -> Pair(
                ContextCompat.getColor(this, R.color.status_orange_bg),
                ContextCompat.getColor(this, R.color.status_orange)
            )
            StatusType.CHALLENGE -> Pair(
                ContextCompat.getColor(this, R.color.status_blue_bg),
                ContextCompat.getColor(this, R.color.status_blue)
            )
            StatusType.ERROR_NETWORK -> Pair(
                ContextCompat.getColor(this, R.color.status_red_bg),
                ContextCompat.getColor(this, R.color.status_red)
            )
            StatusType.CONNECTING -> Pair(
                ContextCompat.getColor(this, R.color.status_blue_bg),
                ContextCompat.getColor(this, R.color.status_blue)
            )
            StatusType.READY, StatusType.IDLE -> Pair(
                ContextCompat.getColor(this, R.color.status_neutral_bg),
                ContextCompat.getColor(this, R.color.text_primary)
            )
        }

        binding.cardStatus.setCardBackgroundColor(statusBgColor)
        binding.tvStatusMessage.setTextColor(statusTextColor)

        // Challenge action button visibility
        if (state.statusType == StatusType.CHALLENGE || (!state.challengeUrl.isNullOrBlank() && !state.wifiConnected.not() && state.internetState == InternetState.AUTHENTICATION_REQUIRED)) {
            binding.btnOpenChallenge.visibility = View.VISIBLE
        } else {
            binding.btnOpenChallenge.visibility = View.GONE
        }

        // Auto-login switch sync
        if (binding.switchAutoLogin.isChecked != state.autoLoginEnabled) {
            binding.switchAutoLogin.isChecked = state.autoLoginEnabled
        }

        // Connect / Disconnect button & Loading spinner
        if (state.isLoading) {
            binding.btnConnect.isEnabled = false
            binding.btnConnect.text = ""
            binding.progressBar.visibility = View.VISIBLE
        } else {
            binding.btnConnect.isEnabled = state.wifiConnected
            binding.progressBar.visibility = View.GONE

            if (state.internetState == InternetState.CONNECTED) {
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnConnect.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_red)
                )
            } else {
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.accent_green)
                )
            }
        }
    }

    private fun showCredentialsDialog() {
        val dialogBinding = DialogCredentialsBinding.inflate(LayoutInflater.from(this))
        val currentCreds = viewModel.getSavedCredentials()
        val detectedPortal = viewModel.uiState.value.portalUrl

        dialogBinding.tilPassword.hint = getString(R.string.password_hint)

        // Prefill existing credentials
        if (currentCreds != null) {
            dialogBinding.etUsername.setText(currentCreds.username)
            // Prefill saved password so user can see and edit it, and eye icon toggle works
            dialogBinding.etPassword.setText(currentCreds.password)
            dialogBinding.tilPassword.helperText = getString(R.string.password_helper_existing)
            dialogBinding.btnClearCredentials.visibility = View.VISIBLE

            // Prefill custom portal URL only if the user explicitly configured a custom override
            if (!currentCreds.portalUrl.isNullOrBlank()) {
                dialogBinding.etPortalUrl.setText(currentCreds.portalUrl)
            } else {
                dialogBinding.etPortalUrl.setText("")
            }
        } else {
            dialogBinding.btnClearCredentials.visibility = View.GONE
            dialogBinding.tilPassword.helperText = null
            dialogBinding.etPortalUrl.setText("")
        }

        // Show auto-detected portal on current Wi-Fi in helper text
        if (!detectedPortal.isNullOrBlank()) {
            dialogBinding.tilPortalUrl.helperText = getString(R.string.portal_url_auto_detected, detectedPortal)
        } else {
            dialogBinding.tilPortalUrl.helperText = getString(R.string.portal_url_auto_helper)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        // Set transparent window background so the custom rounded corners (24dp) show smoothly
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnClearCredentials.setOnClickListener {
            viewModel.clearSavedCredentials()
            dialog.dismiss()
            Toast.makeText(this, "Credentials cleared", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSave.setOnClickListener {
            val username = dialogBinding.etUsername.text?.toString()?.trim() ?: ""
            val password = dialogBinding.etPassword.text?.toString() ?: ""
            val rawPortal = dialogBinding.etPortalUrl.text?.toString()?.trim()

            if (username.isEmpty()) {
                dialogBinding.tilUsername.error = "Please enter your username"
                return@setOnClickListener
            } else {
                dialogBinding.tilUsername.error = null
            }

            if (password.isEmpty()) {
                dialogBinding.tilPassword.error = "Please enter your password"
                return@setOnClickListener
            } else {
                dialogBinding.tilPassword.error = null
            }

            // If empty OR matching the auto-detected portal on this Wi-Fi, save as null
            // so the app stays in dynamic auto-detect mode across all Wi-Fi networks!
            val portalToSave = if (rawPortal.isNullOrBlank() || rawPortal == detectedPortal) {
                null
            } else {
                rawPortal
            }

            viewModel.saveCredentials(username, password, portalToSave)
            dialog.dismiss()
            Toast.makeText(this, "Credentials saved securely ✓", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun openPortalInBrowser(portalUrl: String) {
        try {
            val validUrl = if (portalUrl.startsWith("http://") || portalUrl.startsWith("https://")) {
                portalUrl
            } else {
                "http://$portalUrl"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLocationPermissionForSsid() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
                // Request once to give Android permission to return the SSID name
                requestLocationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }
}
