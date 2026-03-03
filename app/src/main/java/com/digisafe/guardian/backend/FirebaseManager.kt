package com.digisafe.guardian.backend

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FirebaseManager: Singleton backend architect for DIGISAFE.
 * Responsible for atomic multi-path updates, offline persistence, and resilient data sync.
 */
object FirebaseManager {

    private const val TAG = "FirebaseManager"
    private const val MAX_RETRIES = 2 // Reduced retries for faster feedback
    private const val INITIAL_BACKOFF_MS = 1000L

    private var persistenceInitialized = false

    private val database: FirebaseDatabase by lazy {
        val db = FirebaseDatabase.getInstance()
        if (!persistenceInitialized) {
            try {
                db.setPersistenceEnabled(true)
                persistenceInitialized = true
                Log.d(TAG, "Firebase Persistence Enabled")
            } catch (e: Exception) {
                // This can happen if the database was already accessed. Not fatal.
                Log.w(TAG, "Persistence already enabled or failed: ${e.message}")
            }
        }
        db
    }

    private fun getBaseRef(): DatabaseReference = database.reference

    /**
     * Executes a multi-path atomic update with exponential backoff.
     * Uses withContext(Dispatchers.IO) for network-bound operations.
     */
    private suspend fun updateAtomic(updates: Map<String, Any?>): Boolean = withContext(Dispatchers.IO) {
        var currentDelay = INITIAL_BACKOFF_MS
        repeat(MAX_RETRIES) { attempt ->
            try {
                // await() on Firebase Tasks waits for server acknowledgment.
                // With persistence enabled, the operation is queued locally even if this times out.
                getBaseRef().updateChildren(updates).await()
                return@withContext true
            } catch (ce: CancellationException) {
                // Rethrow cancellation immediately so withTimeout works correctly
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Atomic update error (Attempt ${attempt + 1}): ${e.message}")
                
                if (attempt == MAX_RETRIES - 1) return@withContext false
                
                try {
                    delay(currentDelay)
                    currentDelay *= 2
                } catch (ce: CancellationException) {
                    throw ce
                }
            }
        }
        false
    }

    /**
     * Atomically pushes a HIGH risk alert, logs call metadata, and initializes approval state.
     */
    suspend fun pushHighRiskEvent(
        userId: String,
        callData: Map<String, Any>,
        riskScore: Double
    ): Boolean {
        val eventId = getBaseRef().child("users/$userId/alerts").push().key ?: return false
        val timestamp = ServerValue.TIMESTAMP

        val updates = mutableMapOf<String, Any?>()
        updates["users/$userId/alerts/$eventId"] = mapOf(
            "type" to "HIGH_RISK",
            "riskScore" to riskScore,
            "timestamp" to timestamp,
            "status" to "PENDING"
        )
        updates["users/$userId/call_logs/$eventId"] = callData + mapOf(
            "timestamp" to timestamp,
            "eventId" to eventId
        )
        updates["users/$userId/approvals/$eventId"] = mapOf(
            "state" to "AWAITING_GUARDIAN",
            "limit" to "RESTRICTED",
            "timestamp" to timestamp
        )

        return updateAtomic(updates)
    }

    suspend fun logCallMetadata(userId: String, logId: String, metadata: Map<String, Any>): Boolean {
        val updates = mapOf("users/$userId/call_logs/$logId" to metadata)
        return updateAtomic(updates)
    }

    suspend fun createTransactionEntry(userId: String, txData: Map<String, Any>): Boolean {
        val txId = getBaseRef().child("users/$userId/transactions").push().key ?: return false
        val updates = mapOf("users/$userId/transactions/$txId" to txData)
        return updateAtomic(updates)
    }

    suspend fun updateApprovalStateAtomic(
        userId: String,
        eventId: String,
        newState: String
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val ref = getBaseRef().child("users/$userId/approvals/$eventId")
        val auth = FirebaseAuth.getInstance()
        
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(mutableData: MutableData): Transaction.Result {
                val allowedStates = listOf("APPROVED", "DENIED", "TIMEOUT")
                if (newState !in allowedStates) return Transaction.abort()

                val currentData = mutableData.value as? Map<String, Any> ?: return Transaction.abort()
                val currentState = currentData["state"] as? String ?: return Transaction.abort()

                if (currentState != "PENDING") return Transaction.abort()

                val updates = currentData.toMutableMap().apply {
                    put("state", newState)
                    put("resolvedAt", ServerValue.TIMESTAMP)
                    put("resolvedBy", auth.currentUser?.uid ?: "system_timeout")
                }

                mutableData.value = updates
                return Transaction.success(mutableData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) {
                    continuation.resume(false)
                } else {
                    continuation.resume(committed)
                }
            }
        })
    }

    suspend fun registerGuardian(userId: String, guardian: Map<String, Any>): Boolean {
        val guardianPhone = guardian["phone"] as? String ?: return false
        val guardianKey = guardianPhone.replace("+", "").replace(" ", "")
        
        val updates = mutableMapOf<String, Any?>()
        updates["users/$userId/guardians/$guardianKey"] = guardian + mapOf(
            "timestamp" to ServerValue.TIMESTAMP
        )
        
        return updateAtomic(updates)
    }

    suspend fun updateFcmToken(userId: String, token: String): Boolean {
        val updates = mapOf(
            "users/$userId/profile/fcmToken" to token,
            "users/$userId/profile/lastTokenUpdate" to ServerValue.TIMESTAMP
        )
        return updateAtomic(updates)
    }

    fun keepSynced(userId: String) {
        getBaseRef().child("users/$userId/profile").keepSynced(true)
        getBaseRef().child("users/$userId/alerts").keepSynced(true)
    }
}
