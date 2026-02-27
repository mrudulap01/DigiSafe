package com.digisafe.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.digisafe.app.DigiSafeApp
import com.digisafe.app.R
import com.digisafe.app.core.HighRiskManager
import com.digisafe.app.core.RiskEngine
import com.digisafe.app.guardian.GuardianNotifier
import com.digisafe.app.shield.TransactionLimitManager
import com.digisafe.app.ui.MainActivity
import com.digisafe.app.ui.OverlayManager

/**
 * CallMonitorService — Foreground service that monitors phone call state.
 *
 * Features:
 * - Detects incoming/outgoing calls
 * - Tracks call duration
 * - Checks if caller is unknown (contacts lookup)
 * - Periodically recalculates risk via RiskEngine
 * - Triggers overlay + guardian alert on HIGH_RISK
 * - Forced auto-termination escalation: if risk stays HIGH for
 *   [ESCALATION_TIMEOUT_MS], automatically attempts call termination
 * - Activates transaction limits during HIGH_RISK
 */
class CallMonitorService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private var phoneStateListener: PhoneStateListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var riskUpdateRunnable: Runnable? = null
    private var escalationRunnable: Runnable? = null
    private var overlayManager: OverlayManager? = null
    private var guardianNotifier: GuardianNotifier? = null
    private var hasShownOverlay = false
    private var hasSentGuardianAlert = false
    private var hasEscalated = false
    private var highRiskStartTime: Long = 0L

    companion object {
        private const val TAG = "CallMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val RISK_UPDATE_INTERVAL_MS = 5000L  // Re-evaluate risk every 5 seconds
        private const val ESCALATION_TIMEOUT_MS = 3 * 60 * 1000L  // 3 minutes of continuous HIGH_RISK

        fun start(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        overlayManager = OverlayManager(this)
        guardianNotifier = GuardianNotifier(this)

        // Listen for risk state changes
        HighRiskManager.addStateChangeListener(riskStateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Monitoring calls for your safety"))
        startPhoneStateMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopPhoneStateMonitoring()
        stopRiskUpdates()
        stopEscalationTimer()
        HighRiskManager.removeStateChangeListener(riskStateListener)
        overlayManager?.dismissAll()
    }

    @Suppress("DEPRECATION")
    private fun startPhoneStateMonitoring() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        Log.d(TAG, "Incoming call: $phoneNumber")
                        val isUnknown = !isContactKnown(phoneNumber)
                        HighRiskManager.onCallStarted(phoneNumber, isUnknown)
                        startRiskUpdates()
                        updateNotification("📞 Monitoring active call...")
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.d(TAG, "Call answered / outgoing")
                        if (HighRiskManager.isCallActive.value != true) {
                            HighRiskManager.onCallStarted(phoneNumber, true)
                            startRiskUpdates()
                        }
                        updateNotification("📞 Call in progress — monitoring...")
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d(TAG, "Call ended")
                        HighRiskManager.onCallEnded(this@CallMonitorService)
                        stopRiskUpdates()
                        stopEscalationTimer()
                        overlayManager?.dismissAll()
                        resetFlags()
                        updateNotification("Monitoring calls for your safety")
                    }
                }
            }
        }

        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun stopPhoneStateMonitoring() {
        phoneStateListener?.let {
            @Suppress("DEPRECATION")
            telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
        }
    }

    /**
     * Periodically recalculate risk during active call.
     */
    private fun startRiskUpdates() {
        riskUpdateRunnable = object : Runnable {
            override fun run() {
                if (HighRiskManager.isCallActive.value == true) {
                    HighRiskManager.recalculateRisk()
                    handler.postDelayed(this, RISK_UPDATE_INTERVAL_MS)
                }
            }
        }
        handler.postDelayed(riskUpdateRunnable!!, RISK_UPDATE_INTERVAL_MS)
    }

    private fun stopRiskUpdates() {
        riskUpdateRunnable?.let { handler.removeCallbacks(it) }
        riskUpdateRunnable = null
    }

    /**
     * Start the escalation timer when HIGH_RISK is first triggered.
     * After [ESCALATION_TIMEOUT_MS], automatically attempt call termination.
     */
    private fun startEscalationTimer() {
        if (escalationRunnable != null) return // Already running

        highRiskStartTime = System.currentTimeMillis()
        Log.d(TAG, "Escalation timer started — will auto-terminate in ${ESCALATION_TIMEOUT_MS / 1000}s")

        escalationRunnable = Runnable {
            if (HighRiskManager.riskLevel.value == RiskEngine.RiskLevel.HIGH_RISK &&
                HighRiskManager.isCallActive.value == true &&
                !hasEscalated
            ) {
                Log.w(TAG, "⚠️ ESCALATION: HIGH_RISK persisted for ${ESCALATION_TIMEOUT_MS / 1000}s — auto-terminating call")
                hasEscalated = true

                // Attempt auto call termination with fail-safe
                CallTerminator.endCallWithFailSafe(this) {
                    // Fail-safe: show maximum lock overlay
                    overlayManager?.showLockOverlay()
                }

                updateNotification("🚨 ESCALATION — Call auto-terminated for your safety!")
            }
        }
        handler.postDelayed(escalationRunnable!!, ESCALATION_TIMEOUT_MS)
    }

    private fun stopEscalationTimer() {
        escalationRunnable?.let { handler.removeCallbacks(it) }
        escalationRunnable = null
        highRiskStartTime = 0L
    }

    private fun resetFlags() {
        hasShownOverlay = false
        hasSentGuardianAlert = false
        hasEscalated = false
        TransactionLimitManager.reset()
    }

    /**
     * Check if the phone number is in the user's contacts.
     */
    private fun isContactKnown(phoneNumber: String?): Boolean {
        if (phoneNumber.isNullOrBlank()) return false
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor: Cursor? = contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )
            val found = cursor?.moveToFirst() ?: false
            cursor?.close()
            found
        } catch (e: Exception) {
            Log.e(TAG, "Error checking contacts", e)
            false
        }
    }

    private val riskStateListener = object : HighRiskManager.OnRiskStateChangeListener {
        override fun onRiskStateChanged(
            newLevel: RiskEngine.RiskLevel,
            score: Float,
            callerNumber: String?,
            durationSecs: Long
        ) {
            when (newLevel) {
                RiskEngine.RiskLevel.HIGH_RISK -> {
                    // Show overlay if not already shown
                    if (!hasShownOverlay) {
                        overlayManager?.showHighRiskOverlay(
                            callerNumber = callerNumber,
                            durationSecs = durationSecs,
                            riskScore = score
                        )
                        hasShownOverlay = true
                    }

                    // Send guardian alert if not already sent
                    if (!hasSentGuardianAlert) {
                        guardianNotifier?.sendAlert(
                            callerNumber = callerNumber,
                            durationSecs = durationSecs,
                            riskScore = score
                        )
                        hasSentGuardianAlert = true
                        HighRiskManager.incrementThreatsBlocked(this@CallMonitorService)
                    }

                    // Activate transaction limits
                    TransactionLimitManager.onHighRiskTriggered()
                    TransactionLimitManager.activateTemporaryLimit(this@CallMonitorService)

                    // Start escalation timer
                    startEscalationTimer()

                    updateNotification("🚨 HIGH RISK — Possible scam detected!")
                }
                RiskEngine.RiskLevel.WARNING -> {
                    updateNotification("⚠️ WARNING — Suspicious call activity")
                }
                RiskEngine.RiskLevel.SAFE -> {
                    stopEscalationTimer()
                    updateNotification("Monitoring calls for your safety")
                }
            }
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, DigiSafeApp.CHANNEL_ID)
            .setContentTitle("🛡️ DigiSafe Protection")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
