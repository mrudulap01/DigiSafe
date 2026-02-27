package com.digisafe.guardian.setup

import android.content.Context
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.digisafe.guardian.backend.FirebaseManager
import com.digisafe.guardian.databinding.ActivityGuardianSetupBinding
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Guardian Data Model
 * PRODUCTION NOTE: We use a Map-friendly structure for Firebase atomic updates.
 */
data class Guardian(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val relationship: String,
    val isSynced: Boolean = false
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "phone" to phone,
        "relationship" to relationship
    )
}

/**
 * GuardianSetupActivity: Secure registration flow for the primary user's guardian.
 * Implements offline-first storage and tamper-resistant local caching.
 */
class GuardianSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianSetupBinding
    private lateinit var encryptedPrefs: EncryptedSharedPreferences
    
    // Simple rate limiting state
    private var attemptCount = 0
    private var lastAttemptTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initSecurity()
        setupListeners()
    }

    /**
     * SECURITY DECISION: Use Jetpack Security's EncryptedSharedPreferences.
     * AEC256_SIV for keys ensures deterministic but secure lookups.
     * AES256_GCM for values provides authenticated encryption (confidentiality + integrity).
     */
    private fun initSecurity() {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        encryptedPrefs = EncryptedSharedPreferences.create(
            this,
            "secure_guardian_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    private fun setupListeners() {
        binding.btnRegister.setOnClickListener {
            handleRegistration()
        }
    }

    private fun handleRegistration() {
        if (isRateLimited()) {
            showToast("Too many attempts. Please wait.")
            return
        }

        val name = binding.etGuardianName.text.toString().trim()
        val phone = binding.etGuardianPhone.text.toString().trim()
        val relation = binding.etRelationship.text.toString().trim()

        if (!validateInputs(name, phone, relation)) return

        val guardian = Guardian(name = name, phone = phone, relationship = relation)
        saveAndSync(guardian)
    }

    /**
     * VALIDATION LOGIC:
     * 1. Indian Phone Format: +91 or 10 digits.
     * 2. Self-Linking Prevention: User cannot be their own guardian.
     * 3. Duplicate Prevention: Check local encrypted storage.
     */
    private fun validateInputs(name: String, phone: String, relation: String): Boolean {
        if (name.isEmpty() || phone.isEmpty() || relation.isEmpty()) {
            showToast("All fields are mandatory.")
            return false
        }

        // Regex for Indian phone numbers (10 digits, optional +91)
        val indiaPhoneRegex = Regex("^(\\+91)?[6-9]\\d{9}$")
        if (!indiaPhoneRegex.matches(phone)) {
            showToast("Invalid Indian phone number.")
            return false
        }

        val currentUserPhone = encryptedPrefs.getString("user_phone", "")
        if (phone == currentUserPhone) {
            showToast("Security Violation: You cannot be your own guardian.")
            return false
        }

        // Duplicate Check
        if (encryptedPrefs.contains("guardian_$phone")) {
            showToast("Guardian already registered.")
            return false
        }

        return true
    }

    /**
     * OFFLINE-FIRST STRATEGY: 
     * 1. Store encrypted locally immediately.
     * 2. Attempt background sync via FirebaseManager.
     * 3. If offline, Firebase's internal queue handles persistence.
     */
    private fun saveAndSync(guardian: Guardian) {
        // Local Save (Encrypted)
        encryptedPrefs.edit().apply {
            putString("guardian_${guardian.phone}", guardian.name)
            putString("last_guardian_id", guardian.id)
            apply()
        }

        val userId = getUniqueUserId()

        lifecycleScope.launch {
            binding.btnRegister.isEnabled = false
            val success = FirebaseManager.registerGuardian(userId, guardian.toMap())
            
            if (success) {
                showToast("Guardian linked successfully!")
                finish()
            } else {
                // FirebaseManager handles offline queueing, but we notify user of pending sync
                showToast("Saved locally. Will sync when online.")
                finish()
            }
        }
    }

    /**
     * Identity Management: Retrieval of unique ID.
     * Fallback to UUID if Auth is not yet initialized for testing.
     */
    private fun getUniqueUserId(): String {
        var id = encryptedPrefs.getString("internal_user_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            encryptedPrefs.edit().putString("internal_user_id", id).apply()
        }
        return id!!
    }

    private fun isRateLimited(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAttemptTime > 60000) {
            attemptCount = 0
        }
        attemptCount++
        lastAttemptTime = now
        return attemptCount > 3
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
