package com.digisafe.guardian.approvals

import android.util.Log
import com.google.firebase.database.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ApprovalManager: Orchestrates the secure transaction approval lifecycle.
 * Ensures fraud-safe terminal states and resilient listener management.
 */
class ApprovalManager {

    private val TAG = "ApprovalManager"
    private val database = FirebaseDatabase.getInstance()
    
    // tracks active listeners and their references for precise cleanup
    private val activeListeners = ConcurrentHashMap<String, Pair<DatabaseReference, ValueEventListener>>()
    
    // atomic guards to guarantee exactly-once execution of terminal logic for each event
    private val terminalGuards = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * Starts a terminal monitoring cycle for a specific approval event.
     * Guaranteed to fire the callback only once for states APPROVED, DENIED, or TIMEOUT.
     */
    fun startApprovalCycle(
        userId: String,
        eventId: String,
        onTerminalState: (String) -> Unit
    ) {
        val guard = terminalGuards.getOrPut(eventId) { AtomicBoolean(false) }
        
        Log.d(TAG, "ApprovalManager: Listening for event: $eventId")
        
        val path = "users/$userId/approvals/$eventId"
        val ref = database.getReference(path)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val state = snapshot.child("state").getValue(String::class.java) ?: return
                
                // Terminal State Detection
                if (state == "APPROVED" || state == "DENIED" || state == "TIMEOUT") {
                    if (guard.compareAndSet(false, true)) {
                        Log.i(TAG, "ApprovalManager: Event $eventId reached terminal state: $state")
                        
                        // Remove Firebase listener immediately after terminal state
                        removeListener(eventId)
                        
                        onTerminalState(state)
                    } else {
                        Log.d(TAG, "ApprovalManager: Duplicate terminal processing suppressed")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "ApprovalManager: Error for $eventId: ${error.message}")
                removeListener(eventId)
            }
        }

        activeListeners[eventId] = ref to listener
        ref.addValueEventListener(listener)
    }

    /**
     * Safely detaches the listener from Firebase and removes it from the tracking map.
     */
    private fun removeListener(eventId: String) {
        activeListeners.remove(eventId)?.let { (ref, listener) ->
            ref.removeEventListener(listener)
            Log.d(TAG, "ApprovalManager: Removed Firebase listener for event: $eventId")
        }
    }

    /**
     * Lifecycle-safe cleanup to prevent memory leaks.
     */
    fun cleanup() {
        activeListeners.forEach { (eventId, pair) ->
            val (ref, listener) = pair
            ref.removeEventListener(listener)
            Log.d(TAG, "ApprovalManager: Removed Firebase listener for event: $eventId")
        }
        activeListeners.clear()
        terminalGuards.clear()
    }
}
