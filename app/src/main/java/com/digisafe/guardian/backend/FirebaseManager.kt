package com.digisafe.guardian.backend

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import android.util.Log

/**
 * FirebaseManager: Singleton backend architect for DIGISAFE.
 * Responsible for atomic multi-path updates, offline persistence, and resilient data sync.
 * 
 * DESIGN DECISION: We use multi-path updates to ensure consistency across alerts, logs, 
 * and transactions. This prevents "partial state" where an alert is sent but not logged.
 */
object FirebaseManager {

    private const val TAG = "FirebaseManager"
    private const val MAX_RETRIES = 3
    private const val INITIAL_BACKOFF_MS = 1000L

    private val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance().apply {
            // Enable offline persistence for seamless GSM call operation in low-connectivity areas
            setPersistenceEnabled(true)
        }
    }

    private fun getBaseRef(): DatabaseReference = database.reference

    /**
     * Executes a multi-path atomic update with exponential backoff.
     * Prevents UI blocking and ensures eventual consistency even on network failures.
     */
    private suspend fun updateAtomic(updates: Map<String, Any?>): Boolean {
        var currentDelay = INITIAL_BACKOFF_MS
        repeat(MAX_RETRIES) { attempt ->
            try {
                getBaseRef().updateChildren(updates).await()
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Atomic update failed (Attempt ${attempt + 1}): ${e.message}")
                if (attempt == MAX_RETRIES - 1) return false
                delay(currentDelay)
                currentDelay *= 2
            }
        }
        return false
    }

    /**
     * Atomically pushes a HIGH risk alert, logs call metadata, and initializes approval state.
     * 
     * @param userId The primary user (senior citizen) ID.
     * @param callData Metadata about the intercepted scam call.
     * @param riskScore AI-calculated risk probability.
     */
    suspend fun pushHighRiskEvent(
        userId: String,
        callData: Map<String, Any>,
        riskScore: Double
    ): Boolean {
        val eventId = getBaseRef().child("users/$userId/alerts").push().key ?: return false
        val timestamp = ServerValue.TIMESTAMP

        val updates = mutableMapOf<String, Any?>()

        // 1. Alert Node: Immediate notification trigger
        updates["users/$userId/alerts/$eventId"] = mapOf(
            "type" to "HIGH_RISK",
            "riskScore" to riskScore,
            "timestamp" to timestamp,
            "status" to "PENDING"
        )

        // 2. Call Logs: Evidence for the dashboard
        updates["users/$userId/call_logs/$eventId"] = callData + mapOf(
            "timestamp" to timestamp,
            "eventId" to eventId
        )

        // 3. Approval State: Lock high-risk transaction attempts
        updates["users/$userId/approvals/$eventId"] = mapOf(
            "state" to "AWAITING_GUARDIAN",
            "limit" to "RESTRICTED",
            "timestamp" to timestamp
        )

        return updateAtomic(updates)
    }

    /**
     * Legacy wrapper for granular call logging.
     */
    suspend fun logCallMetadata(userId: String, logId: String, metadata: Map<String, Any>): Boolean {
        val updates = mapOf("users/$userId/call_logs/$logId" to metadata)
        return updateAtomic(updates)
    }

    /**
     * Records a specific transaction attempt triggered by high-risk call context.
     */
    suspend fun createTransactionEntry(userId: String, txData: Map<String, Any>): Boolean {
        val txId = getBaseRef().child("users/$userId/transactions").push().key ?: return false
        val updates = mapOf("users/$userId/transactions/$txId" to txData)
        return updateAtomic(updates)
    }

    /**
     * Updates the approval state based on Guardian response or timeout.
     */
    suspend fun updateApprovalState(userId: String, eventId: String, newState: String): Boolean {
        val path = "users/$userId/approvals/$eventId/state"
        val updates = mapOf(path to newState)
        return updateAtomic(updates)
    }

    /**
     * Registers a guardian for a specific user.
     * Uses atomic update to ensure the guardian is linked and the user's profile is updated.
     */
    suspend fun registerGuardian(userId: String, guardian: Map<String, Any>): Boolean {
        val guardianPhone = guardian["phone"] as? String ?: return false
        val updates = mutableMapOf<String, Any?>()
        
        // Use phone number as key (sanitized) or unique ID to prevent duplicates
        val guardianKey = guardianPhone.replace("+", "").replace(" ", "")
        
        updates["users/$userId/guardians/$guardianKey"] = guardian + mapOf(
            "timestamp" to ServerValue.TIMESTAMP
        )
        
        return updateAtomic(updates)
    }

    /**
     * Updates the device's FCM token for a specific user.
     * Essential for routing high-risk alerts to the correct guardian device.
     */
    suspend fun updateFcmToken(userId: String, token: String): Boolean {
        val updates = mapOf(
            "users/$userId/profile/fcmToken" to token,
            "users/$userId/profile/lastTokenUpdate" to ServerValue.TIMESTAMP
        )
        return updateAtomic(updates)
    }

    /**
     * Sync Strategy: Firebase RTDB handles offline queueing automatically.
     * This manager ensures the queue is processed with atomic integrity.
     */
    fun keepSynced(userId: String) {
        getBaseRef().child("users/$userId/profile").keepSynced(true)
        getBaseRef().child("users/$userId/alerts").keepSynced(true)
    }
}
