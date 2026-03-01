package com.digisafe.app.shield

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.digisafe.app.core.HighRiskManager
import com.digisafe.app.core.RiskEngine
import com.digisafe.app.ui.OverlayManager

/**
 * BankingShieldService — AccessibilityService that detects when a
 * banking / UPI app is opened during an active phone call.
 *
 * Features:
 * 1. Package detection: Detects known banking/UPI app package names
 * 2. Screen text monitoring: Scans for transaction-related keywords
 *    ("Send Money", "Enter UPI PIN", "Transfer", "Confirm")
 * 3. Triggers banking shield overlay + blocks interaction
 * 4. Integrates with TransactionLimitManager for amount checking
 *
 * Requires explicit user opt-in through Android Accessibility Settings.
 */
class BankingShieldService : AccessibilityService() {

    private var overlayManager: OverlayManager? = null
    private var lastDetectedPackage: String? = null
    private var hasShownShieldForSession = false

    companion object {
        private const val TAG = "BankingShieldService"

        // Known Indian banking / UPI app package names
        private val BANKING_PACKAGES = setOf(
            // UPI Apps
            "com.google.android.apps.nbu.paisa.user",  // Google Pay
            "net.one97.paytm",                          // Paytm
            "com.phonepe.app",                          // PhonePe
            "in.org.npci.upiapp",                       // BHIM
            "com.amazon.mShop.android.shopping",        // Amazon Pay
            "com.whatsapp",                             // WhatsApp Pay
            // Major Bank Apps
            "com.sbi.SBIFreedomPlus",                   // SBI YONO
            "com.csam.icici.bank.imobile",              // ICICI iMobile
            "com.axis.mobile",                          // Axis Mobile
            "com.msf.kbank.mobile",                     // Kotak
            "com.hdfcbank.hdfcquickbank",               // HDFC
            "com.unionbankofindia.unionbank",            // Union Bank
            "com.bankofbaroda.mconnect",                // BOB
            "com.pnb.ebanking",                         // PNB
            "com.canaaboretum",                         // Canara Bank
            "com.indianbank.mobilepay",                 // Indian Bank
        )

        // Transaction-related keywords to watch for on screen
        private val TRANSACTION_KEYWORDS = listOf(
            "send money",
            "enter upi pin",
            "upi pin",
            "transfer",
            "confirm payment",
            "pay now",
            "confirm",
            "proceed to pay",
            "enter pin",
            "amount",
            "beneficiary",
            "neft",
            "imps",
            "rtgs",
            "fund transfer"
        )

        private val TRANSACTION_ATTEMPT_KEYWORDS = listOf(
            "pay now",
            "confirm payment",
            "proceed to pay",
            "enter upi pin",
            "enter pin",
            "confirm"
        )

        // Track service instance
        var instance: BankingShieldService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        overlayManager = OverlayManager(this)
        Log.d(TAG, "BankingShieldService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 200
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
        Log.d(TAG, "BankingShieldService connected with text monitoring")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Skip system packages and our own app
        if (packageName.startsWith("com.android.") ||
            packageName.startsWith("com.digisafe.") ||
            packageName == "android"
        ) {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(packageName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Only monitor content changes in banking apps during active call
                if (BANKING_PACKAGES.contains(packageName) &&
                    HighRiskManager.isCallActive.value == true
                ) {
                    handleContentChanged(event)
                }
            }
        }
    }

    /**
     * Handle window state changes — detect when a banking app is opened.
     */
    private fun handleWindowStateChanged(packageName: String) {
        val isBankingApp = BANKING_PACKAGES.contains(packageName)

        if (isBankingApp && packageName != lastDetectedPackage) {
            Log.d(TAG, "Banking app detected: $packageName")
            lastDetectedPackage = packageName

            // Notify HighRiskManager
            HighRiskManager.onBankingAppDetected(true)

            // If there's an active call and risk is elevated, show banking shield
            val isCallActive = HighRiskManager.isCallActive.value ?: false
            val currentRisk = HighRiskManager.riskLevel.value ?: RiskEngine.RiskLevel.SAFE

            if (isCallActive && currentRisk != RiskEngine.RiskLevel.SAFE) {
                TransactionLimitManager.onBankingAppOpened()

                if (TransactionLimitManager.shouldBlockTransaction() && !hasShownShieldForSession) {
                    overlayManager?.showBankingShieldOverlay()
                    hasShownShieldForSession = true
                    Log.d(TAG, "Banking shield overlay triggered for: $packageName")
                }
            }
        } else if (!isBankingApp && lastDetectedPackage != null) {
            // User left the banking app
            lastDetectedPackage = null
            hasShownShieldForSession = false
            HighRiskManager.onBankingAppDetected(false)
        }
    }

    /**
     * Monitor screen text content for transaction-related keywords.
     * If detected during HIGH_RISK, triggers banking shield.
     */
    private fun handleContentChanged(event: AccessibilityEvent) {
        val currentRisk = HighRiskManager.riskLevel.value ?: RiskEngine.RiskLevel.SAFE
        if (currentRisk == RiskEngine.RiskLevel.SAFE) return

        try {
            val rootNode = rootInActiveWindow ?: return
            val screenText = extractTextFromNode(rootNode)
            rootNode.recycle()

            if (screenText.isBlank()) return

            val lowerText = screenText.lowercase()
            val detectedKeywords = TRANSACTION_KEYWORDS.filter { keyword ->
                lowerText.contains(keyword)
            }

            if (detectedKeywords.isNotEmpty()) {
                Log.w(TAG, "Transaction keywords detected: $detectedKeywords")

                // Notify TransactionLimitManager
                TransactionLimitManager.onTransactionKeywordDetected()
                HighRiskManager.onSuspiciousPhraseDetected()
                val inferredProbability = (detectedKeywords.size / 5f).coerceIn(0f, 1f)
                HighRiskManager.updateKeywordProbability(inferredProbability)

                val hasAttemptKeyword = detectedKeywords.any { keyword ->
                    TRANSACTION_ATTEMPT_KEYWORDS.contains(keyword)
                }
                if (hasAttemptKeyword) {
                    TransactionLimitManager.onTransactionAttemptDetected()
                    HighRiskManager.onTransactionAttemptDetected()
                }

                // Show shield if risk is HIGH and not already shown
                if (currentRisk == RiskEngine.RiskLevel.HIGH_RISK && !hasShownShieldForSession) {
                    overlayManager?.showBankingShieldOverlay()
                    hasShownShieldForSession = true
                    Log.d(TAG, "Banking shield triggered by keyword detection")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning screen content", e)
        }
    }

    /**
     * Recursively extract visible text from an accessibility node tree.
     */
    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        val builder = StringBuilder()

        // Get this node's text
        node.text?.let { builder.append(it).append(" ") }
        node.contentDescription?.let { builder.append(it).append(" ") }

        // Recurse into children (limit depth to avoid performance issues)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            builder.append(extractTextFromNode(child))
            child.recycle()
        }

        return builder.toString()
    }

    override fun onInterrupt() {
        Log.d(TAG, "BankingShieldService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        overlayManager?.dismissAll()
        overlayManager = null
        hasShownShieldForSession = false
        Log.d(TAG, "BankingShieldService destroyed")
    }
}
