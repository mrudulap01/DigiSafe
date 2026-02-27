package com.digisafe.core

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * PRODUCTION-GRADE FIREBASE BACKEND MANAGER for DIGISAFE
 * 
 * ARCHITECTURAL DECISIONS:
 * 1. Singleton Pattern: Ensures a single point of truth for DB references and persistence config.
 * 2. Atomic Multi-path Updates: Prevents partial writes (e.g., alert exists but log doesn't).
 * 3. Offline Persistence: Enabled at initialization to queue writes while cellular connectivity is low.
 * 4. Exponential Backoff: Manual retry wrapper for critical transaction orchestration.
 * 5. Denormalized Schema: Optimized for guardian real-time listening without nested data bloat.
 */
object FirebaseManager {
    private const val TAG = "FirebaseManager"
    private val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance().apply {
            // CRITICAL: Ensure writes are cached locally even if the app process is killed
            setPersistenceEnabled(true)
        }
    }

    private val rootRef: DatabaseReference = database.reference

    /**
     * ATOMIC ORCHESTRATION: High-Risk Alert + Call Metadata + Pending Approval
     * Use multi-path update to ensure data integrity across the user's tree.
     */
    suspend fun orchestrateHighRiskEvent(
        uid: String,
        callerNumber: String,
        riskScore: Int,
        callDuration: Long,
        location: Map<String, Double>
    ): Result<String> {
        val eventId = UUID.randomUUID().toString()
        val timestamp = ServerValue.TIMESTAMP

        val alertData = mapOf(
            "eventId" to eventId,
            "callerNumber" to callerNumber,
            "riskScore" to riskScore,
            "timestamp" to timestamp,
            "status" to "ACTIVE",
            "location" to location
        )

        val logData = mapOf(
            "callerNumber" to callerNumber,
            "duration" to callDuration,
            "timestamp" to timestamp,
            "riskLevel" to "HIGH"
        )

        val pendingApproval = mapOf(
            "eventId" to eventId,
            "status" to "PENDING",
            "requestedAt" to timestamp,
            "expiresAt" to (System.currentTimeMillis() + 30000) // 30s timeout logic seed
        )

        val childUpdates = hashMapOf<String, Any>(
            "users/$uid/alerts/$eventId" to alertData,
            "users/$uid/call_logs/$eventId" to logData,
            "users/$uid/approvals/$eventId" to pendingApproval
        )

        return executeWithRetry {
            rootRef.updateChildren(childUpdates).await()
            eventId
        }
    }

    /**
     * Pure push for isolated risk alerts.
     */
    suspend fun pushHighRiskAlert(uid: String, alert: Map<String, Any>): Result<Unit> {
        val alertId = UUID.randomUUID().toString()
        return executeWithRetry {
            rootRef.child("users/$uid/alerts/$alertId").setValue(alert).await()
        }
    }

    /**
     * Secure log entry for evidence metadata.
     */
    suspend fun logCallMetadata(uid: String, log: Map<String, Any>): Result<Unit> {
        return executeWithRetry {
            rootRef.child("users/$uid/call_logs").push().setValue(log).await()
        }
    }

    /**
     * Initialize a transaction entry that requires guardian oversight.
     */
    suspend fun createTransactionEntry(uid: String, transaction: Map<String, Any>): Result<String> {
        val txId = UUID.randomUUID().toString()
        return executeWithRetry {
            rootRef.child("users/$uid/transactions/$txId").setValue(transaction).await()
            txId
        }
    }

    /**
     * Update approval state (Guardian Action).
     * Used by GuardianDashboardActivity to sync response back to user device.
     */
    suspend fun updateApprovalState(uid: String, eventId: String, status: String): Result<Unit> {
        val updates = mapOf(
            "status" to status,
            "resolvedAt" to ServerValue.TIMESTAMP
        )
        return executeWithRetry {
            rootRef.child("users/$uid/approvals/$eventId").updateChildren(updates).await()
        }
    }

    /**
     * Securely register a guardian for a primary user.
     */
    suspend fun registerGuardian(uid: String, guardian: com.digisafe.guardian.Guardian): Result<Unit> {
        return executeWithRetry {
            rootRef.child("users/$uid/guardians/${guardian.id}").setValue(guardian).await()
        }
    }

    /**
     * EXPONENTIAL BACKOFF RETRY MECHANISM
     * Ensures critical cloud sync recovers from transient network failures.
     */
    private suspend fun <T> executeWithRetry(
        maxRetries: Int = 3,
        initialDelay: Long = 1000L,
        block: suspend () -> T
    ): Result<T> {
        var currentDelay = initialDelay
        repeat(maxRetries) { attempt ->
            try {
                return Result.success(block())
            } catch (e: Exception) {
                Log.e(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                if (attempt == maxRetries - 1) return Result.failure(e)
            }
            kotlinx.coroutines.delay(currentDelay)
            currentDelay *= 2 // Exponential backoff
        }
        return Result.failure(Exception("Max retries exceeded"))
    }
}
