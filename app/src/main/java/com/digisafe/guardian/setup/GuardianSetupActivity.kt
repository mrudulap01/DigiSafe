package com.digisafe.guardian.setup

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.digisafe.guardian.MainActivity
import com.digisafe.guardian.backend.FirebaseManager
import com.digisafe.guardian.databinding.ActivityGuardianSetupBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Guardian Data Model - Enhanced for Debugging
 */
data class Guardian(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val relationship: String
) {
    fun toMap(): Map<String, Any> {
        val map = mapOf(
            "id" to id,
            "name" to name,
            "phone" to phone,
            "relationship" to relationship
        )
        Log.d("GuardianDebug", "Guardian.toMap() generated: $map")
        return map
    }
}

class GuardianSetupActivity : AppCompatActivity() {

    private val TAG = "GuardianSetupDebug"
    private lateinit var binding: ActivityGuardianSetupBinding
    private lateinit var encryptedPrefs: EncryptedSharedPreferences

    private var attemptCount = 0
    private var lastAttemptTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "STAGE 1: Activity onCreate")
        
        try {
            initSecurity()
            
            // NAVIGATION LOGIC: If guardian already registered, redirect to MainActivity (which handles dashboard routing)
            if (encryptedPrefs.contains("last_guardian_id")) {
                Log.i(TAG, "Guardian already registered. Redirecting to MainActivity.")
                navigateToMain()
                return
            }

            binding = ActivityGuardianSetupBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Log.d(TAG, "STAGE 1: ViewBinding success. root=${binding.root}")

            setupListeners()
        } catch (e: Exception) {
            Log.e(TAG, "FATAL: Initialization failed at STAGE 1", e)
            showToast("Initialization error: ${e.message}")
        }
    }

    private fun initSecurity() {
        Log.i(TAG, "STAGE 2: initSecurity")
        try {
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
            Log.d(TAG, "STAGE 2: EncryptedSharedPreferences ready")
        } catch (e: Exception) {
            Log.e(TAG, "STAGE 2 ERROR: Security layer failure", e)
            showToast("Security initialization error.")
        }
    }

    private fun setupListeners() {
        Log.i(TAG, "STAGE 3: setupListeners")
        binding.btnRegister.setOnClickListener {
            // Immediate visual feedback to isolate UI blocking vs Logic failure
            Log.i(TAG, "EVENT: btnRegister Clicked")
            Toast.makeText(this, "[DEBUG] Click Detected", Toast.LENGTH_SHORT).show()
            handleRegistration()
        }
    }

    private fun handleRegistration() {
        Log.i(TAG, "STAGE 4: handleRegistration Entry")
        
        if (isRateLimited()) {
            Log.w(TAG, "STAGE 4 ABORT: Rate limited. Attempts=$attemptCount")
            showToast("Too many attempts. Please wait.")
            return
        }

        val name = binding.etGuardianName.text.toString().trim()
        val phone = binding.etGuardianPhone.text.toString().trim()
        val relation = binding.etRelationship.text.toString().trim()

        Log.d(TAG, "STAGE 4: Captured Inputs -> name='$name', phone='$phone', relation='$relation'")

        if (!validateInputs(name, phone, relation)) {
            Log.w(TAG, "STAGE 4 ABORT: Validation failed")
            return
        }

        val guardian = Guardian(name = name, phone = phone, relationship = relation)
        Log.i(TAG, "STAGE 4 SUCCESS: Guardian object created. ID=${guardian.id}")
        saveAndSync(guardian)
    }

    private fun validateInputs(name: String, phone: String, relation: String): Boolean {
        Log.d(TAG, "STAGE 4.1: Starting Validation")
        
        if (name.isEmpty() || phone.isEmpty() || relation.isEmpty()) {
            Log.w(TAG, "Validation failed: Empty fields")
            showToast("All fields are mandatory.")
            return false
        }

        val indiaPhoneRegex = Regex("^(\\+91)?[6-9]\\d{9}$")
        if (!indiaPhoneRegex.matches(phone)) {
            Log.w(TAG, "Validation failed: Invalid phone format '$phone'")
            showToast("Invalid Indian phone number.")
            return false
        }

        val currentUserPhone = try {
            encryptedPrefs.getString("user_phone", "")
        } catch (e: Exception) {
            Log.e(TAG, "Security Error reading user_phone", e)
            ""
        }
        
        if (phone == currentUserPhone) {
            Log.w(TAG, "Validation failed: Guardian phone matches user phone")
            showToast("Security Violation: You cannot be your own guardian.")
            return false
        }

        val userId = getUniqueUserId()
        Log.d(TAG, "STAGE 4.2: UserId for check: $userId")

        if (encryptedPrefs.contains("guardian_${userId}_$phone")) {
            Log.w(TAG, "Validation failed: Duplicate entry in local prefs")
            showToast("Guardian already registered for this user.")
            return false
        }

        Log.d(TAG, "STAGE 4.3: All Validations Passed")
        return true
    }

    private fun saveAndSync(guardian: Guardian) {
        val userId = getUniqueUserId()
        Log.i(TAG, "STAGE 5: saveAndSync Entry. User=$userId")

        try {
            // Attempt Local Save First
            encryptedPrefs.edit().apply {
                putString("guardian_${userId}_${guardian.phone}", guardian.name)
                putString("last_guardian_id", guardian.id)
                apply()
            }
            Log.d(TAG, "STAGE 5: Local SharedPrefs save success")
        } catch (e: Exception) {
            Log.e(TAG, "STAGE 5 ERROR: Local save failed", e)
        }

        // Launch Coroutine with Protection
        lifecycleScope.launch {
            Log.i(TAG, "STAGE 6: Coroutine Launched on lifecycleScope")
            binding.btnRegister.isEnabled = false
            
            try {
                Log.d(TAG, "STAGE 6: Calling Firebase with 10s timeout...")
                
                // timeout prevents silent hanging if network is dead or Firebase hangs
                val success = withTimeout(10000L) {
                    FirebaseManager.registerGuardian(userId, guardian.toMap())
                }
                
                Log.i(TAG, "STAGE 7: Firebase Response Received. Success=$success")

                if (success) {
                    showToast("Guardian linked successfully!")
                    navigateToMain()
                } else {
                    Log.w(TAG, "FINAL: Firebase returned false")
                    showToast("Saved locally. Will sync when online.")
                    navigateToMain()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "STAGE 6 TIMEOUT: Firebase took too long (>10s)", e)
                showToast("Connection slow. Saved locally.")
                navigateToMain()
            } catch (e: Exception) {
                Log.e(TAG, "STAGE 6 FATAL: Firebase Coroutine crashed", e)
                showToast("Connection error. Saved locally.")
                navigateToMain()
            } finally {
                Log.d(TAG, "STAGE 6 COMPLETE: Re-enabling button")
                binding.btnRegister.isEnabled = true
            }
        }
    }

    private fun navigateToMain() {
        Log.d(TAG, "FINAL: Navigating to MainActivity")
        startActivity(Intent(this@GuardianSetupActivity, MainActivity::class.java))
        finish()
    }

    private fun getUniqueUserId(): String {
        return try {
            var id = encryptedPrefs.getString("internal_user_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                encryptedPrefs.edit().putString("internal_user_id", id).apply()
                Log.d(TAG, "UserId: Generated New -> $id")
            } else {
                Log.d(TAG, "UserId: Retrieved Existing -> $id")
            }
            id!!
        } catch (e: Exception) {
            Log.e(TAG, "UserId Error: SharedPrefs failure", e)
            val fallback = "fb_${System.currentTimeMillis()}"
            Log.w(TAG, "UserId: Using fallback -> $fallback")
            fallback
        }
    }

    private fun isRateLimited(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAttemptTime > 60000) {
            attemptCount = 0
            Log.d(TAG, "RateLimit: Window Reset")
        }
        attemptCount++
        lastAttemptTime = now
        val limited = attemptCount > 3
        if (limited) Log.w(TAG, "RateLimit: BLOCKED (Count=$attemptCount)")
        return limited
    }

    private fun showToast(msg: String) {
        Log.i(TAG, "TOAST: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
