package com.digisafe.guardian.evidence

import android.util.Base64
import android.util.Log
import com.digisafe.guardian.backend.FirebaseManager
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * EvidencePackage: The encrypted, tamper-proof structure stored in Firebase.
 */
data class EvidencePackage(
    val encryptedPayload: String,
    val iv: String,
    val salt: String,
    val hmac: String,
    val algorithmVersion: String = "AES-256-GCM-v1",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "encryptedPayload" to encryptedPayload,
        "iv" to iv,
        "salt" to salt,
        "hmac" to hmac,
        "algorithmVersion" to algorithmVersion,
        "createdAt" to createdAt
    )
}

/**
 * EvidenceVaultManager: Production-grade forensic evidence management.
 * 
 * SECURITY DESIGN:
 * 1. Key Derivation: PBKDF2WithHmacSHA256 is used to derive a strong key from a master secret.
 * 2. Encryption: AES-256-GCM provides confidentiality and built-in authentication (Auth Tag).
 * 3. Integrity: A separate HMAC-SHA256 is generated to detect tampering before decryption.
 */
object EvidenceVaultManager {

    private const val TAG = "EvidenceVaultManager"
    private const val KEY_ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val IV_LENGTH = 12 // Standard for GCM
    private const val SALT_LENGTH = 32

    /**
     * Build and upload a secure evidence package.
     */
    suspend fun secureEvidence(
        userId: String,
        eventId: String,
        evidenceData: Map<String, Any>,
        masterSecret: String
    ): String? {
        try {
            // 1. Convert data to JSON string
            val jsonData = JSONObject(evidenceData).toString()

            // 2. Generate Random Salt and IV
            val salt = generateRandomBytes(SALT_LENGTH)
            val iv = generateRandomBytes(IV_LENGTH)

            // 3. Derive Key
            val key = deriveKey(masterSecret, salt)

            // 4. Encrypt Payload
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)
            val encryptedBytes = cipher.doFinal(jsonData.toByteArray())

            val encryptedPayloadBase64 = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
            val ivBase64 = Base64.encodeToString(iv, Base64.DEFAULT)
            val saltBase64 = Base64.encodeToString(salt, Base64.DEFAULT)

            // 5. Generate HMAC for Integrity
            val hmac = generateHmac(encryptedPayloadBase64, key)

            // 6. Assemble Package
            val pkg = EvidencePackage(
                encryptedPayload = encryptedPayloadBase64,
                iv = ivBase64,
                salt = saltBase64,
                hmac = hmac
            )

            // 7. Atomic Upload
            val database = FirebaseDatabase.getInstance()
            database.getReference("users/$userId/evidence/$eventId")
                .setValue(pkg.toMap()).await()

            Log.d(TAG, "Evidence securely vaulted for event: $eventId")
            return eventId

        } catch (e: Exception) {
            Log.e(TAG, "Failed to vault evidence: ${e.message}")
            return null
        }
    }

    /**
     * Decrypt and verify an existing evidence package.
     */
    suspend fun decryptEvidence(
        pkg: EvidencePackage,
        masterSecret: String
    ): String? {
        try {
            val salt = Base64.decode(pkg.salt, Base64.DEFAULT)
            val iv = Base64.decode(pkg.iv, Base64.DEFAULT)
            val encryptedBytes = Base64.decode(pkg.encryptedPayload, Base64.DEFAULT)

            // 1. Derive Key
            val key = deriveKey(masterSecret, salt)

            // 2. Verify HMAC Integrity
            val calculatedHmac = generateHmac(pkg.encryptedPayload, key)
            if (calculatedHmac != pkg.hmac) {
                Log.e(TAG, "TAMPER DETECTION: HMAC mismatch!")
                return null
            }

            // 3. Decrypt
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)

            return String(decryptedBytes)

        } catch (e: Exception) {
            Log.e(TAG, "Decryption/Verification failed: ${e.message}")
            return null
        }
    }

    // --- Private Security Helpers ---

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, KEY_ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    private fun generateHmac(data: String, key: SecretKey): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
        val hmacBytes = mac.doFinal(data.toByteArray())
        return Base64.encodeToString(hmacBytes, Base64.DEFAULT)
    }

    private fun generateRandomBytes(length: Int): ByteArray {
        val random = SecureRandom()
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return bytes
    }
}
