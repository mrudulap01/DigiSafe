package com.digisafe.app.shield

import android.util.Log
import com.digisafe.app.core.HighRiskManager
import com.digisafe.app.core.RiskEngine

/**
 * TransactionLimitManager — Transaction firewall that blocks
 * suspicious financial activity during high-risk calls.
 *
 * Rule: If a UPI/banking app is opened within [BLOCK_WINDOW_MS]
 * of a HIGH_RISK state, the transaction should be blocked.
 */
object TransactionLimitManager {

    private const val TAG = "TransactionLimitMgr"
    private const val BLOCK_WINDOW_MS = 5 * 60 * 1000L  // 5 minutes

    // Timestamp when banking app was last opened
    private var lastBankingAppOpenTime: Long = 0L

    // Timestamp when HIGH_RISK was first triggered
    private var highRiskTriggerTime: Long = 0L

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
     * Determine if the current transaction should be blocked.
     *
     * Returns true if:
     * - Risk level is HIGH_RISK, AND
     * - Banking app was opened within BLOCK_WINDOW_MS of the high-risk trigger
     */
    fun shouldBlockTransaction(): Boolean {
        val currentRisk = HighRiskManager.riskLevel.value ?: RiskEngine.RiskLevel.SAFE

        if (currentRisk != RiskEngine.RiskLevel.HIGH_RISK) {
            return false
        }

        val now = System.currentTimeMillis()

        // Block if banking app opened during or shortly after high-risk state
        val timeSinceHighRisk = now - highRiskTriggerTime
        val timeSinceBankingOpen = now - lastBankingAppOpenTime

        val shouldBlock = timeSinceHighRisk < BLOCK_WINDOW_MS ||
                timeSinceBankingOpen < BLOCK_WINDOW_MS

        Log.d(TAG, "shouldBlockTransaction: $shouldBlock " +
                "(timeSinceHighRisk=${timeSinceHighRisk}ms, " +
                "timeSinceBankingOpen=${timeSinceBankingOpen}ms)")

        return shouldBlock
    }

    /**
     * Get a user-facing reason for why the transaction is blocked.
     */
    fun getBlockReason(): String {
        return "This transaction may be fraud. A suspicious call was detected recently. " +
                "Please contact your guardian before proceeding."
    }

    /**
     * Reset all state — called when risk returns to SAFE.
     */
    fun reset() {
        lastBankingAppOpenTime = 0L
        highRiskTriggerTime = 0L
        Log.d(TAG, "Transaction limit state reset")
    }
}
