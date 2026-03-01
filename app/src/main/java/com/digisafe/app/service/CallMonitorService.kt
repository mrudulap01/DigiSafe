package com.digisafe.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.digisafe.app.DigiSafeApp
import com.digisafe.app.core.HighRiskManager
import com.digisafe.app.core.RiskEngine
import com.digisafe.app.guardian.FirebaseManager
import com.digisafe.app.guardian.GuardianNotifier
import com.digisafe.app.shield.TransactionLimitManager
import com.digisafe.app.ui.MainActivity
import com.digisafe.app.ui.OverlayManager

/**
 * Foreground service that monitors call state and enforces interventions.
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

    companion object {
        private const val TAG = "CallMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val RISK_UPDATE_INTERVAL_MS = 5000L
        private const val ESCALATION_TIMEOUT_MS = 3 * 60 * 1000L
        private const val CONTINUOUS_PHRASE_THRESHOLD = 3

        fun start(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
        HighRiskManager.addStateChangeListener(riskStateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Monitoring calls for your safety")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (error: SecurityException) {
                Log.e(TAG, "Typed foreground start failed, falling back.", error)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

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
                        val isUnknown = !isContactKnown(phoneNumber)
                        HighRiskManager.onCallStarted(phoneNumber, isUnknown)
                        startRiskUpdates()
                        updateNotification("Monitoring active call.")
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (HighRiskManager.isCallActive.value != true) {
                            HighRiskManager.onCallStarted(phoneNumber, true)
                            startRiskUpdates()
                        }
                        updateNotification("Call in progress. Monitoring risk.")
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
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

    private fun startRiskUpdates() {
        riskUpdateRunnable = object : Runnable {
            override fun run() {
                if (HighRiskManager.isCallActive.value == true) {
                    HighRiskManager.recalculateRisk()
                    checkImmediateForcedIntervention()
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

    private fun startEscalationTimer() {
        if (escalationRunnable != null) return
        escalationRunnable = Runnable {
            if (HighRiskManager.riskLevel.value == RiskEngine.RiskLevel.HIGH_RISK &&
                HighRiskManager.isCallActive.value == true &&
                !hasEscalated
            ) {
                forceInterventionNow("High-risk timeout reached. Call terminated.")
            }
        }
        handler.postDelayed(escalationRunnable!!, ESCALATION_TIMEOUT_MS)
    }

    private fun stopEscalationTimer() {
        escalationRunnable?.let { handler.removeCallbacks(it) }
        escalationRunnable = null
    }

    private fun resetFlags() {
        hasShownOverlay = false
        hasSentGuardianAlert = false
        hasEscalated = false
        TransactionLimitManager.reset()
    }

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
                null,
                null,
                null
            )
            val found = cursor?.moveToFirst() ?: false
            cursor?.close()
            found
        } catch (error: Exception) {
            Log.e(TAG, "Contact lookup failed.", error)
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
                    if (!hasShownOverlay) {
                        overlayManager?.showHighRiskOverlay(callerNumber, durationSecs, score)
                        hasShownOverlay = true
                    }
                    if (!hasSentGuardianAlert) {
                        FirebaseManager.sendHighRiskAlert(
                            context = this@CallMonitorService,
                            callerNumber = callerNumber,
                            durationSecs = durationSecs,
                            riskScore = score
                        )
                        guardianNotifier?.sendAlert(callerNumber, durationSecs, score)
                        hasSentGuardianAlert = true
                        HighRiskManager.incrementThreatsBlocked(this@CallMonitorService)
                    }
                    TransactionLimitManager.onHighRiskTriggered()
                    TransactionLimitManager.activateTemporaryLimit(this@CallMonitorService)
                    startEscalationTimer()
                    checkImmediateForcedIntervention()
                    updateNotification("High risk detected. Intervention active.")
                }
                RiskEngine.RiskLevel.WARNING -> {
                    updateNotification("Warning. Suspicious call activity.")
                }
                RiskEngine.RiskLevel.SAFE -> {
                    stopEscalationTimer()
                    updateNotification("Monitoring calls for your safety")
                }
            }
        }
    }

    private fun checkImmediateForcedIntervention() {
        if (hasEscalated || HighRiskManager.isCallActive.value != true) return
        if (HighRiskManager.riskLevel.value != RiskEngine.RiskLevel.HIGH_RISK) return

        val phraseContinuation =
            HighRiskManager.getSuspiciousPhraseHits() >= CONTINUOUS_PHRASE_THRESHOLD
        val transactionAttempt =
            HighRiskManager.hasTransactionAttemptDetected() ||
                TransactionLimitManager.hasTransactionAttemptDetected()

        when {
            transactionAttempt ->
                forceInterventionNow("Transaction attempt detected during high risk.")
            phraseContinuation ->
                forceInterventionNow("Continuous suspicious phrases detected.")
        }
    }

    private fun forceInterventionNow(reason: String) {
        if (hasEscalated) return
        hasEscalated = true
        Log.w(TAG, "Forced intervention: $reason")
        CallTerminator.endCallWithFailSafe(this) {
            overlayManager?.showLockOverlay()
        }
        updateNotification(reason)
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, DigiSafeApp.CHANNEL_ID)
            .setContentTitle("DigiSafe Protection")
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
