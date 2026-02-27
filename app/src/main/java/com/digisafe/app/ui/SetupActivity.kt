package com.digisafe.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.digisafe.app.R
import com.digisafe.app.shield.BankingShieldService
import com.google.android.material.button.MaterialButton

/**
 * SetupActivity — Onboarding screen for first-time users.
 *
 * Walks through:
 * 1. Permission disclosure (why each permission is needed)
 * 2. Runtime permission requests
 * 3. Overlay permission (special intent)
 * 4. Accessibility service enabling (for BankingShieldService)
 * 5. Stores "setup completed" flag
 * 6. Navigates to MainActivity
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "digisafe_prefs"
        private const val KEY_SETUP_DONE = "setup_completed"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.filter { it.value }.keys
        val denied = results.filter { !it.value }.keys

        if (denied.isEmpty()) {
            Toast.makeText(this, "✅ All permissions granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Some permissions were denied. You can grant them later in Settings.",
                Toast.LENGTH_LONG
            ).show()
        }

        // After runtime permissions, handle overlay permission
        requestOverlayPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val btnGrantPermissions = findViewById<MaterialButton>(R.id.btnGrantPermissions)
        val btnContinue = findViewById<MaterialButton>(R.id.btnContinue)

        btnGrantPermissions.setOnClickListener {
            requestAllPermissions()
        }

        btnContinue.setOnClickListener {
            completeSetup()
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Filter to only ungranted permissions
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            Toast.makeText(this, "✅ All permissions already granted!", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Please enable overlay permission for DigiSafe",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            // Check accessibility next
            promptAccessibilityIfNeeded()
        }
    }

    private fun promptAccessibilityIfNeeded() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "Please enable DigiSafe in Accessibility Settings to detect banking apps",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager =
            getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )

        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == BankingShieldService::class.java.name
        }
    }

    private fun completeSetup() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_DONE, true)
            .apply()

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
