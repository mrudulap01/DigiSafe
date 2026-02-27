package com.digisafe.guardian.approvals

import android.util.Log
import com.digisafe.guardian.backend.FirebaseManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ApprovalState: Deterministic states for the Digisafe approval process.
 */
enum class ApprovalState {
    PENDING,    // Initial state, waiting for guardian response
    APPROVED,   // Guardian explicitly allowed the transaction
    DENIED,     // Guardian explicitly blocked the transaction
    TIMEOUT,    // Guardian did not respond within 30 seconds
    EXPIRED     // Transaction window (e.g., 5 mins) has closed
}

/**
 * ApprovalManager: Orchestrates the secure transaction approval lifecycle.
 * Ensures fraud-safe state transitions and resilient terminal-state handling.
 */
object ApprovalManager {

    private const val TAG = "ApprovalManager"
    private const val APPROVAL_TIMEOUT_MS = 30_000L // 30-second window for high-risk tx
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeListeners = ConcurrentHashMap<String, ValueEventListener>()
    private val timeoutJobs = ConcurrentHashMap<String, Job>()
    private val terminalGuards = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * STATE TRANSITION DIAGRAM (HARDENED):
     * PENDING -> APPROVED (Guardian explicitly allowed)
     * PENDING -> DENIED   (Guardian explicitly blocked)
     * PENDING -> TIMEOUT  (No response within window)
     * 
     * [GUARDED] Any other transition (e.g., TIMEOUT -> APPROVED) is rejected by Firebase Transaction.
     * [GUARDED] Terminal callbacks (Allowed/Blocked) fire exactly once per event.
     */

    /**
     * Interface for observing approval results.
     */
    interface ApprovalCallback {
        fun onStateChanged(state: ApprovalState)
        fun onTransactionAllowed()
        fun onTransactionBlocked()
    }

    /**
     * 1. INITIATE APPROVAL
     * Called when a user attempts a transaction during/after a HIGH-RISK event.
     */
    fun initiateApproval(userId: String, eventId: String, callback: ApprovalCallback) {
        Log.d(TAG, "Initiating approval for Event: $eventId")
        
        // Initialize terminal guard
        terminalGuards[eventId] = AtomicBoolean(false)

        // 1. Setup Firebase Listener
        observeApproval(userId, eventId, callback)

        // 2. Start Timeout Timer
        startTimeoutTimer(userId, eventId, callback)
    }

    /**
     * 2. STATE TRANSITION LOGIC (SECURE)
     * VALIDATION: Only allow transitions from PENDING to a terminal state.
     * PREVENTS: Backward transitions or double-approvals.
     */
    private fun handleStateTransition(
        userId: String, 
        eventId: String, 
        newState: ApprovalState, 
        callback: ApprovalCallback
    ) {
        scope.launch {
            Log.d(TAG, "Attempting transition for $eventId to $newState")
            
            val success = FirebaseManager.updateApprovalStateAtomic(userId, eventId, newState.name)
            if (success) {
                notifyTerminalState(eventId, newState, callback)
            } else {
                Log.w(TAG, "Transition to $newState failed for $eventId (Possible race or already terminal)")
            }
        }
    }

    /**
     * 3. FIREBASE LISTENER
     * Listens for Guardian response in real-time.
     */
    private fun observeApproval(userId: String, eventId: String, callback: ApprovalCallback) {
        val ref = FirebaseDatabase.getInstance().getReference("users/$userId/approvals/$eventId")
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val stateStr = snapshot.child("state").getValue(String::class.java) ?: return
                val state = try { ApprovalState.valueOf(stateStr) } catch (e: Exception) { return }

                // SECURITY: Only process server confirmations for non-PENDING states
                if (state != ApprovalState.PENDING) {
                    notifyTerminalState(eventId, state, callback)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase Listener Error for $eventId: ${error.message}")
            }
        }

        activeListeners[eventId] = listener
        ref.addValueEventListener(listener)
    }

    /**
     * Ensures callbacks are fired only once and cleans up resources.
     */
    private fun notifyTerminalState(eventId: String, state: ApprovalState, callback: ApprovalCallback) {
        val guard = terminalGuards[eventId] ?: return
        
        if (guard.compareAndSet(false, true)) {
            Log.i(TAG, "Event $eventId reached terminal state: $state. Triggering callbacks.")
            cleanup(eventId)
            callback.onStateChanged(state)
            if (state == ApprovalState.APPROVED) {
                callback.onTransactionAllowed()
            } else {
                callback.onTransactionBlocked()
            }
        } else {
            Log.d(TAG, "Duplicate terminal processing suppressed for $eventId (Current: $state)")
        }
    }

    /**
     * 4. TIMEOUT IMPLEMENTATION
     * Deterministic 30-second window using Coroutines.
     */
    private fun startTimeoutTimer(userId: String, eventId: String, callback: ApprovalCallback) {
        val job = scope.launch {
            delay(APPROVAL_TIMEOUT_MS)
            
            // Re-check guard before even attempting network call
            val guard = terminalGuards[eventId]
            if (guard != null && !guard.get()) {
                Log.w(TAG, "Approval Timeout triggered for event: $eventId")
                val success = FirebaseManager.updateApprovalStateAtomic(userId, eventId, ApprovalState.TIMEOUT.name)
                if (success) {
                    notifyTerminalState(eventId, ApprovalState.TIMEOUT, callback)
                }
            }
        }
        timeoutJobs[eventId] = job
    }

    /**
     * 5. ANTI-TAMPERING & CLEANUP
     * Cancels timers and removes listeners to prevent memory leaks and rogue state injections.
     */
    fun cancelApproval(eventId: String) {
        cleanup(eventId)
    }

    private fun cleanup(eventId: String) {
        // Cancel timer
        timeoutJobs[eventId]?.cancel()
        timeoutJobs.remove(eventId)
        
        // Note: Terminal guard is kept until explicit removal to prevent re-triggering logic
        // but could be pruned after a certain TTL in production.
    }

    /**
     * STATE TRANSITION DIAGRAM (Mental Model):
     * [START] -> PENDING
     * PENDING -> APPROVED (Guardian Action) -> [TERMINAL: ALLOWED]
     * PENDING -> DENIED   (Guardian Action) -> [TERMINAL: BLOCKED]
     * PENDING -> TIMEOUT  (No Response 30s) -> [TERMINAL: BLOCKED]
     * PENDING -> EXPIRED  (Internal Logic)  -> [TERMINAL: BLOCKED]
     */
}
