package com.digisafe.app.shield

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.digisafe.app.core.HighRiskManager
import com.digisafe.app.core.RiskEngine

/**
 * TransactionLimitManager — Transaction firewall that blocks
 * suspicious financial activity during high-risk calls.
 *
 * Features:
 * 1. Time-based blocking: UPI app opened within 5 min of HIGH_RISK
 * 2. Configurable transfer limit: Temporary max ₹1000 (or custom)
 * 3. Guardian approval: Required for amounts above threshold
 * 4. Keyword detection tracking: Blocks when transaction keywords detected
 * 5. SharedPreferences for persistent limit state
 */
object TransactionLimitManager {

    private const val TAG = "TransactionLimitMgr"
    private const val BLOCK_WINDOW_MS = 5 * 60 * 1000L  // 5 minutes
    private const val PREFS_NAME = "digisafe_transaction_limits"
    private const val KEY_TEMP_LIMIT_ACTIVE = "temp_limit_active"
    private const val KEY_TEMP_LIMIT_AMOUNT = "temp_limit_amount"
    private const val KEY_GUARDIAN_APPROVED = "guardian_approved"
    private const val KEY_LIMIT_ACTIVATED_TIME = "limit_activated_time"

    // Default temporary transfer limit (in rupees)
    private const val DEFAULT_TRANSFER_LIMIT = 1000

    // Timestamp when banking app was last opened
    private var lastBankingAppOpenTime: Long = 0L

    // Timestamp when HIGH_RISK was first triggered
    private var highRiskTriggerTime: Long = 0L

    // Whether transaction keywords were detected on screen
    private var transactionKeywordDetected: Boolean = false

    /**
     * Initialize with context for SharedPreferences access.
     */
    fun init(context: Context) {
        val prefs = getPrefs(context)
        // Clear stale locks older than the block window
        val activatedTime = prefs.getLong(KEY_LIMIT_ACTIVATED_TIME, 0L)
        if (activatedTime > 0 && System.currentTimeMillis() - activatedTime > BLOCK_WINDOW_MS) {
            clearTemporaryLimit(context)
        }
    }

    /**
     * Called when a banking app is detected as opened.
     */
    fun onBankingAppOpened() {
        lastBankingAppOpenTime = System.currentTimeMillis()
        Log.d(TAG, "Banking app opened at $lastBankingAppOpenTime")
    }

    /**
     * Called when HIGH_RISK state is first triggered.
     */
    fun onHighRiskTriggered() {
        if (highRiskTriggerTime == 0L) {
            highRiskTriggerTime = System.currentTimeMillis()
            Log.d(TAG, "HIGH_RISK triggered at $highRiskTriggerTime")
        }
    }

    /**
     * Called when transaction-related keywords are detected on screen.
     */
    fun onTransactionKeywordDetected() {
        transactionKeywordDetected = true
        Log.w(TAG, "Transaction keyword detected on screen during risky call")
    }

    /**
     * Determine if the current transaction should be blocked.
     */
    fun shouldBlockTransaction(): Boolean {
        val currentRisk = HighRiskManager.riskLevel.value ?: RiskEngine.RiskLevel.SAFE

        if (currentRisk == RiskEngine.RiskLevel.SAFE) {
            return false
        }

        val now = System.currentTimeMillis()

        // Block conditions:
        // 1. HIGH_RISK active and banking app opened recently
        val timeSinceHighRisk = if (highRiskTriggerTime > 0) now - highRiskTriggerTime else Long.MAX_VALUE
        val timeSinceBankingOpen = if (lastBankingAppOpenTime > 0) now - lastBankingAppOpenTime else Long.MAX_VALUE

        val timeBasedBlock = timeSinceHighRisk < BLOCK_WINDOW_MS ||
                timeSinceBankingOpen < BLOCK_WINDOW_MS

        // 2. Transaction keywords detected during risk
        val keywordBlock = transactionKeywordDetected && currentRisk == RiskEngine.RiskLevel.HIGH_RISK

        val shouldBlock = timeBasedBlock || keywordBlock

        Log.d(TAG, "shouldBlockTransaction: $shouldBlock " +
                "(timeBlock=$timeBasedBlock, keywordBlock=$keywordBlock)")

        return shouldBlock
    }

    /**
     * Activate temporary transfer limit during HIGH_RISK state.
     * Stores limit in SharedPreferences for persistence.
     */
    fun activateTemporaryLimit(context: Context, limitAmount: Int = DEFAULT_TRANSFER_LIMIT) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_TEMP_LIMIT_ACTIVE, true)
            .putInt(KEY_TEMP_LIMIT_AMOUNT, limitAmount)
            .putBoolean(KEY_GUARDIAN_APPROVED, false)
            .putLong(KEY_LIMIT_ACTIVATED_TIME, System.currentTimeMillis())
            .apply()

        Log.d(TAG, "Temporary transfer limit activated: ₹$limitAmount")
    }

    /**
     * Check if temporary transfer limit is active.
     */
    fun isTemporaryLimitActive(context: Context): Boolean {
        val prefs = getPrefs(context)
        val active = prefs.getBoolean(KEY_TEMP_LIMIT_ACTIVE, false)
        if (!active) return false

        // Auto-expire after block window
        val activatedTime = prefs.getLong(KEY_LIMIT_ACTIVATED_TIME, 0L)
        if (System.currentTimeMillis() - activatedTime > BLOCK_WINDOW_MS) {
            clearTemporaryLimit(context)
            return false
        }
        return true
    }

    /**
     * Get the current temporary transfer limit amount.
     */
    fun getTransferLimit(context: Context): Int {
        return getPrefs(context).getInt(KEY_TEMP_LIMIT_AMOUNT, DEFAULT_TRANSFER_LIMIT)
    }

    /**
     * Check if a transaction amount exceeds the temporary limit.
     * Returns true if the amount is above the limit AND guardian hasn't approved.
     */
    fun isAmountAboveLimit(context: Context, amountRupees: Int): Boolean {
        if (!isTemporaryLimitActive(context)) return false
        val limit = getTransferLimit(context)
        val guardianApproved = getPrefs(context).getBoolean(KEY_GUARDIAN_APPROVED, false)

        return amountRupees > limit && !guardianApproved
    }

    /**
     * Mark that guardian has approved the transaction.
     */
    fun setGuardianApproval(context: Context, approved: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_GUARDIAN_APPROVED, approved)
            .apply()
        Log.d(TAG, "Guardian approval set: $approved")
    }

    /**
     * Check if guardian has approved the current transaction.
     */
    fun isGuardianApproved(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GUARDIAN_APPROVED, false)
    }

    /**
     * Clear the temporary transfer limit.
     */
    fun clearTemporaryLimit(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_TEMP_LIMIT_ACTIVE, false)
            .putBoolean(KEY_GUARDIAN_APPROVED, false)
            .putLong(KEY_LIMIT_ACTIVATED_TIME, 0L)
            .apply()
        Log.d(TAG, "Temporary transfer limit cleared")
    }

    /**
     * Get a user-facing reason for why the transaction is blocked.
     */
    fun getBlockReason(context: Context): String {
        val limit = getTransferLimit(context)
        return if (isTemporaryLimitActive(context)) {
            "⚠️ Temporary transfer limit of ₹$limit is active due to a " +
                    "suspicious call. Guardian approval is required for larger amounts."
        } else {
            "This transaction may be fraud. A suspicious call was detected recently. " +
                    "Please contact your guardian before proceeding."
        }
    }

    /**
     * Reset all state — called when risk returns to SAFE.
     */
    fun reset() {
        lastBankingAppOpenTime = 0L
        highRiskTriggerTime = 0L
        transactionKeywordDetected = false
        Log.d(TAG, "Transaction limit state reset")
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
