package com.digisafe.app.shield

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.digisafe.app.core.HighRiskManager
import com.digisafe.app.core.RiskEngine
import com.digisafe.app.ui.OverlayManager

/**
 * BankingShieldService — AccessibilityService that detects when a
 * banking / UPI app is opened during an active phone call.
 *
 * When detected during a HIGH_RISK or WARNING state, triggers:
 * 1. HighRiskManager.onBankingAppDetected(true)
 * 2. Banking shield overlay via OverlayManager
 *
 * This service requires explicit user opt-in through Android
 * Accessibility Settings.
 */
class BankingShieldService : AccessibilityService() {

    private var overlayManager: OverlayManager? = null
    private var lastDetectedPackage: String? = null

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
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 200
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
        Log.d(TAG, "BankingShieldService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return

        // Skip system packages and our own app
        if (packageName.startsWith("com.android.") ||
            packageName.startsWith("com.digisafe.") ||
            packageName == "android"
        ) {
            return
        }

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
                // Check if transaction should be blocked
                TransactionLimitManager.onBankingAppOpened()

                if (TransactionLimitManager.shouldBlockTransaction()) {
                    overlayManager?.showBankingShieldOverlay()
                    Log.d(TAG, "Banking shield overlay triggered for: $packageName")
                }
            }
        } else if (!isBankingApp && lastDetectedPackage != null) {
            // User left the banking app
            lastDetectedPackage = null
            HighRiskManager.onBankingAppDetected(false)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "BankingShieldService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        overlayManager?.dismissOverlay()
        overlayManager = null
        Log.d(TAG, "BankingShieldService destroyed")
    }
}
