package com.digisafe.app.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.digisafe.app.R
import com.digisafe.app.core.HighRiskManager
import com.digisafe.app.core.RiskEngine
import com.digisafe.app.guardian.GuardianManager
import com.digisafe.app.service.CallMonitorService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import android.widget.ProgressBar
import android.widget.TextView

/**
 * MainActivity — Main dashboard for DigiSafe.
 *
 * Displays:
 * - Live protection status (SAFE / WARNING / HIGH_RISK)
 * - Protection toggle to start/stop CallMonitorService
 * - Guardian contact form
 * - Privacy disclosure
 * - Protection stats
 *
 * Observes HighRiskManager LiveData for real-time UI updates.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // Views
    private lateinit var cardStatus: MaterialCardView
    private lateinit var tvStatusIcon: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var layoutRiskScore: View
    private lateinit var tvRiskScore: TextView
    private lateinit var progressRisk: ProgressBar
    private lateinit var switchProtection: MaterialSwitch
    private lateinit var tvProtectionStatus: TextView
    private lateinit var etGuardianName: TextInputEditText
    private lateinit var etGuardianPhone: TextInputEditText
    private lateinit var btnSaveGuardian: MaterialButton
    private lateinit var tvCallsMonitored: TextView
    private lateinit var tvThreatsBlocked: TextView

    companion object {
        private const val PREFS_NAME = "digisafe_prefs"
        private const val KEY_SETUP_DONE = "setup_completed"
        private const val KEY_PROTECTION_ENABLED = "protection_enabled"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // First-launch → redirect to setup
        if (!prefs.getBoolean(KEY_SETUP_DONE, false)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        bindViews()
        setupProtectionToggle()
        setupGuardianForm()
        observeRiskState()
        updateStats()

        // Restore protection state
        val wasProtectionOn = prefs.getBoolean(KEY_PROTECTION_ENABLED, false)
        switchProtection.isChecked = wasProtectionOn
        if (wasProtectionOn) {
            startProtection()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStats()
        loadGuardianData()
    }

    private fun bindViews() {
        cardStatus = findViewById(R.id.cardStatus)
        tvStatusIcon = findViewById(R.id.tvStatusIcon)
        tvStatus = findViewById(R.id.tvStatus)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        layoutRiskScore = findViewById(R.id.layoutRiskScore)
        tvRiskScore = findViewById(R.id.tvRiskScore)
        progressRisk = findViewById(R.id.progressRisk)
        switchProtection = findViewById(R.id.switchProtection)
        tvProtectionStatus = findViewById(R.id.tvProtectionStatus)
        etGuardianName = findViewById(R.id.etGuardianName)
        etGuardianPhone = findViewById(R.id.etGuardianPhone)
        btnSaveGuardian = findViewById(R.id.btnSaveGuardian)
        tvCallsMonitored = findViewById(R.id.tvCallsMonitored)
        tvThreatsBlocked = findViewById(R.id.tvThreatsBlocked)
    }

    private fun setupProtectionToggle() {
        switchProtection.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkRequiredPermissions()) {
                    startProtection()
                } else {
                    switchProtection.isChecked = false
                    requestRequiredPermissions()
                }
            } else {
                stopProtection()
            }
        }
    }

    private fun startProtection() {
        CallMonitorService.start(this)
        tvProtectionStatus.text = getString(R.string.protection_active)
        prefs.edit().putBoolean(KEY_PROTECTION_ENABLED, true).apply()
        updateStatusCard(RiskEngine.RiskLevel.SAFE)
    }

    private fun stopProtection() {
        CallMonitorService.stop(this)
        tvProtectionStatus.text = getString(R.string.protection_inactive)
        prefs.edit().putBoolean(KEY_PROTECTION_ENABLED, false).apply()
        layoutRiskScore.visibility = View.GONE
    }

    private fun setupGuardianForm() {
        loadGuardianData()

        btnSaveGuardian.setOnClickListener {
            val name = etGuardianName.text?.toString()?.trim() ?: ""
            val phone = etGuardianPhone.text?.toString()?.trim() ?: ""

            if (name.isBlank() || phone.isBlank()) {
                Toast.makeText(this, "Please enter both name and phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            GuardianManager.saveGuardian(this, name, phone)
            Toast.makeText(this, getString(R.string.guardian_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadGuardianData() {
        val name = GuardianManager.getGuardianName(this)
        val phone = GuardianManager.getGuardianPhone(this)
        if (!name.isNullOrBlank()) etGuardianName.setText(name)
        if (!phone.isNullOrBlank()) etGuardianPhone.setText(phone)
    }

    private fun observeRiskState() {
        HighRiskManager.riskLevel.observe(this) { level ->
            updateStatusCard(level)
        }

        HighRiskManager.riskScore.observe(this) { score ->
            val percentage = (score * 100).toInt()
            tvRiskScore.text = String.format("Risk Score: %d%%", percentage)
            progressRisk.progress = percentage
        }

        HighRiskManager.isCallActive.observe(this) { isActive ->
            if (isActive) {
                layoutRiskScore.visibility = View.VISIBLE
                tvStatusDetail.text = "📞 Active call being monitored..."
            } else {
                layoutRiskScore.visibility = View.GONE
                tvStatusDetail.text = getString(R.string.status_monitoring)
                updateStats()
            }
        }
    }

    private fun updateStatusCard(level: RiskEngine.RiskLevel) {
        when (level) {
            RiskEngine.RiskLevel.SAFE -> {
                tvStatusIcon.text = "🛡️"
                tvStatus.text = getString(R.string.status_safe)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.state_safe_light))
                cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.state_safe_bg))
                cardStatus.strokeColor = ContextCompat.getColor(this, R.color.state_safe)
            }
            RiskEngine.RiskLevel.WARNING -> {
                tvStatusIcon.text = "⚠️"
                tvStatus.text = getString(R.string.status_warning)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.state_warning_light))
                cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.state_warning_bg))
                cardStatus.strokeColor = ContextCompat.getColor(this, R.color.state_warning)
            }
            RiskEngine.RiskLevel.HIGH_RISK -> {
                tvStatusIcon.text = "🚨"
                tvStatus.text = getString(R.string.status_high_risk)
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.state_high_risk_light))
                cardStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.state_high_risk_bg))
                cardStatus.strokeColor = ContextCompat.getColor(this, R.color.state_high_risk)
            }
        }
    }

    private fun updateStats() {
        tvCallsMonitored.text = HighRiskManager.getCallsMonitored(this).toString()
        tvThreatsBlocked.text = HighRiskManager.getThreatsBlocked(this).toString()
    }

    // ── Permissions ─────────────────────────────────────────

    private fun checkRequiredPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        } && Settings.canDrawOverlays(this)
    }

    private fun requestRequiredPermissions() {
        val permissions = getRequiredPermissions()
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }

        // Overlay permission requires special handling
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun getRequiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms
    }
}
