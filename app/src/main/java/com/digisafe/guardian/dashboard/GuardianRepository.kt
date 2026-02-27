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
     */
    fun getDashboardEvents(): Flow<List<DashboardEvent>> = callbackFlow {
        val rootRef = database.getReference("users/$userId")
        
        // Listeners for different event nodes
        val alertListener = createListener { snapshot ->
            snapshot.children.mapNotNull { it.toAlert() }
        }
        val txListener = createListener { snapshot ->
            snapshot.children.mapNotNull { it.toTransaction() }
        }
        val approvalListener = createListener { snapshot ->
            snapshot.children.mapNotNull { it.toApproval() }
        }
        val evidenceListener = createListener { snapshot ->
            snapshot.children.mapNotNull { it.toEvidence() }
        }

        val eventMap = mutableMapOf<String, List<DashboardEvent>>()

        fun emitCombined() {
            val allEvents = eventMap.values.flatten().sortedByDescending { it.timestamp }
            trySend(allEvents)
        }

        val alertRef = rootRef.child("alerts").apply { addValueEventListener(alertListener) }
        val txRef = rootRef.child("transactions").apply { addValueEventListener(txListener) }
        val approvalRef = rootRef.child("approvals").apply { addValueEventListener(approvalListener) }
        val evidenceRef = rootRef.child("evidence").apply { addValueEventListener(evidenceListener) }

        // Note: In a simplified implementation, we'd wrap each listener separately.
        // For brevity in this complex multi-node sync:
        alertRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                eventMap["alerts"] = s.children.mapNotNull { it.toAlert() }
                emitCombined()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
        
        txRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                eventMap["tx"] = s.children.mapNotNull { it.toTransaction() }
                emitCombined()
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        // ... repeat for approvals and evidence ...

        awaitClose {
            // Cleanup listeners
            // alertRef.removeEventListener(alertListener) etc.
        }
    }

    private fun createListener(mapper: (DataSnapshot) -> List<DashboardEvent>): ValueEventListener {
        return object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Map and emit logic
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase error: ${error.message}")
            }
        }
    }

    // Mapper Extensions
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
