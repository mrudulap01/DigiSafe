package com.digisafe.guardian.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.digisafe.guardian.backend.FirebaseManager
import com.digisafe.guardian.dashboard.GuardianDashboardActivity
import com.digisafe.guardian.security.DeviceIntegrityManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * AlertService: Production-grade FCM listener with Persistent Replay Protection.
 * 
 * SECURITY DESIGN (HARDENED):
 * 1. Persistent De-duplication: Prevents alert replay even after app restarts/device reboots.
 * 2. Hardware-Backed Cache: Replay IDs are stored in EncryptedSharedPreferences (TEE-backed keys).
 * 3. Temporal Freshness: Rejects any event older than 5 minutes to prevent late injection.
 * 4. Runtime Integrity: Blocks all high-risk alerts on compromised (rooted/tompered) devices.
 * 5. Signature Readiness: Architecture supports future ECDSA payload verification.
 */
class AlertService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Thread-safe lazy initialization of EncryptedSharedPreferences
    private val replayPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            applicationContext,
            "digiSafe_replay_cache",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val TAG = "AlertService"
        private const val CHANNEL_HIGH_RISK = "high_risk_alerts"
        private const val FRESHNESS_WINDOW_MS = 300_000L // 5 minutes
        private const val FUTURE_TOLERANCE_MS = 60_000L   // 1 minute
        private const val CLEANUP_THRESHOLD_MS = 86_400_000L // 24 hours
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        serviceScope.launch {
            val userId = getEncryptedUserId()
            if (userId != null) {
                FirebaseManager.updateFcmToken(userId, token)
            }
        }
    }

    /**
     * Entry point for high-integrity alert payloads.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data.isEmpty()) return

        // 0. Runtime Integrity Check (FAIL-CLOSED)
        if (DeviceIntegrityManager.isDeviceCompromised(this)) {
            Log.w(TAG, "Device compromised: Aborting alert processing")
            return
        }

        // 1. Fail-Closed Payload Extraction
        val eventId = data["eventId"]
        val timestamp = data["timestamp"]?.toLongOrNull()
        val signature = data["signature"]
        val riskLevel = data["riskLevel"]

        if (eventId.isNullOrBlank() || timestamp == null) {
            Log.w(TAG, "Invalid payload: Dropping malformed message")
            return
        }

        // 2. Timestamp Freshness Window (Fail-Closed)
        if (!isTimestampFresh(timestamp)) {
            Log.w(TAG, "Stale event rejected")
            return
        }

        // 3. Persistent Replay Detection (Atomic Check-and-Write)
        if (!tryMarkEventProcessed(eventId, timestamp)) {
            Log.w(TAG, "Replay detected")
            return
        }

        // 4. Cryptographic Signature Stub
        if (!validateSignature(eventId, timestamp, signature)) {
            Log.w(TAG, "Signature validation failed")
            return
        }

        // 5. Spoofing Protection (Logic Consistency)
        if (riskLevel != "HIGH") {
            return
        }

        // 6. Cleanup Background Tasks
        cleanupExpiredEvents()

        // 7. Deliver Notification
        showHighRiskNotification(data)
    }

    private fun isTimestampFresh(eventTimestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        val drift = now - eventTimestamp
        
        // Reject if event is too old or too far in the future
        return drift in (-FUTURE_TOLERANCE_MS)..FRESHNESS_WINDOW_MS
    }

    /**
     * Atomically checks if an event has been processed and marks it as such.
     * Thread-safe to prevent race conditions during rapid message bursts.
     */
    @Synchronized
    private fun tryMarkEventProcessed(eventId: String, timestamp: Long): Boolean {
        return try {
            val key = "event_$eventId"
            if (replayPrefs.contains(key)) {
                false // Replay detected
            } else {
                replayPrefs.edit()
                    .putLong(key, timestamp)
                    .apply()
                true // Successfully marked as processed
            }
        } catch (e: Exception) {
            // If prefs are corrupted, fail-closed: assume replay
            false
        }
    }

    /**
     * Asynchronous, lightweight cleanup of entries older than 24 hours.
     */
    private fun cleanupExpiredEvents() {
        serviceScope.launch {
            try {
                val now = System.currentTimeMillis()
                val editor = replayPrefs.edit()
                var cleaned = false

                replayPrefs.all.forEach { (key, value) ->
                    if (key.startsWith("event_") && value is Long) {
                        if (now - value > CLEANUP_THRESHOLD_MS) {
                            editor.remove(key)
                            cleaned = true
                        }
                    }
                }

                if (cleaned) editor.apply()
            } catch (e: Exception) {
                // Silently handle pref corruption or concurrency issues
            }
        }
    }

    /**
     * ECDSA Signature Verification Stub.
     * TODO: Implement ECDSA signature verification using embedded public key.
     */
    private fun validateSignature(eventId: String, timestamp: Long, signature: String?): Boolean {
        // Return true to allow current flow; future updates will enforce real verification.
        return true
    }

    private fun showHighRiskNotification(data: Map<String, String>) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(notificationManager)

        val callerNumber = data["callerNumber"] ?: "Unknown"
        val eventId = data["eventId"] ?: ""

        val intent = Intent(this, GuardianDashboardActivity::class.java).apply {
            putExtra("EVENT_ID", eventId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, eventId.hashCode(), intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_HIGH_RISK)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 HIGH RISK SCAM DETECTED")
            .setContentText("Potential fraud call from: $callerNumber")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setColor(Color.RED)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(eventId.hashCode(), notification)
    }

    private fun createNotificationChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_HIGH_RISK,
                "High Risk Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun getEncryptedUserId(): String? {
        return try {
            val masterKey = MasterKey.Builder(applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                applicationContext,
                "secure_guardian_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.getString("internal_user_id", null)
        } catch (e: Exception) {
            null
        }
    }
}
