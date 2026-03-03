package com.digisafe.guardian.dashboard

import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import android.util.Log

/**
 * GuardianRepository: Data layer abstraction for Dashboard monitoring.
 * Encapsulates Firebase listeners and maps snapshots to domain models.
 */
class GuardianRepository(private val userId: String) {

    private val database = FirebaseDatabase.getInstance()
    private val TAG = "GuardianRepository"

    /**
     * Observes all relevant dashboard events in real-time.
     * Uses callbackFlow for reactive integration with ViewModel.
     * Implements multi-node synchronization and proper resource cleanup.
     */
    fun getDashboardEvents(): Flow<List<DashboardEvent>> = callbackFlow {
        val rootRef = database.getReference("users/$userId")
        val eventMap = mutableMapOf<String, List<DashboardEvent>>()

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    // 1. Map Alerts
                    eventMap["alerts"] = snapshot.child("alerts").children.mapNotNull { it.toAlert() }
                    
                    // 2. Map Transactions
                    eventMap["tx"] = snapshot.child("transactions").children.mapNotNull { it.toTransaction() }
                    
                    // 3. Map Approvals
                    eventMap["approvals"] = snapshot.child("approvals").children.mapNotNull { it.toApproval() }
                    
                    // 4. Map Evidence
                    eventMap["evidence"] = snapshot.child("evidence").children.mapNotNull { it.toEvidence() }

                    // 5. Combine and Sort by descending timestamp (latest first)
                    val allEvents = eventMap.values.flatten().sortedByDescending { it.timestamp }
                    
                    Log.d(TAG, "Dashboard updated: ${allEvents.size} events emitted")
                    trySend(allEvents)
                } catch (e: Exception) {
                    Log.e(TAG, "Mapping error in dashboard sync", e)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase sync cancelled: ${error.message}")
                close(error.toException())
            }
        }

        Log.i(TAG, "Attaching root dashboard listener for user: $userId")
        rootRef.addValueEventListener(listener)

        // CRITICAL: Cleanup listener when the flow is closed (e.g., ViewModel cleared)
        awaitClose {
            Log.i(TAG, "Detaching root dashboard listener")
            rootRef.removeEventListener(listener)
        }
    }

    // Mapper Extensions with safety checks
    private fun DataSnapshot.toAlert(): DashboardEvent.Alert? {
        val id = key ?: return null
        val score = child("riskScore").getValue(Double::class.java) ?: 0.0
        val level = child("type").getValue(String::class.java) ?: "UNKNOWN"
        val time = child("timestamp").getValue(Long::class.java) ?: 0L
        return DashboardEvent.Alert(id, score, level, "Phone Hidden", time)
    }

    private fun DataSnapshot.toTransaction(): DashboardEvent.Transaction? {
        val id = key ?: return null
        val amount = child("amount").getValue(Double::class.java) ?: 0.0
        val status = child("status").getValue(String::class.java) ?: "PENDING"
        val merchant = child("merchant").getValue(String::class.java) ?: "Unknown"
        val time = child("timestamp").getValue(Long::class.java) ?: 0L
        return DashboardEvent.Transaction(id, amount, status, merchant, time)
    }

    private fun DataSnapshot.toApproval(): DashboardEvent.Approval? {
        val id = key ?: return null
        val state = child("state").getValue(String::class.java) ?: "PENDING"
        val time = child("timestamp").getValue(Long::class.java) ?: 0L
        return DashboardEvent.Approval(id, state, time)
    }

    private fun DataSnapshot.toEvidence(): DashboardEvent.Evidence? {
        val id = key ?: return null
        val meta = child("encrypted_metadata").getValue(String::class.java) ?: ""
        val time = child("timestamp").getValue(Long::class.java) ?: 0L
        return DashboardEvent.Evidence(id, meta, time)
    }
}
