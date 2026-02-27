package com.digisafe.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.digisafe.core.FirebaseManager
import com.digisafe.guardian.GuardianDashboardActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * PRODUCTION-GRADE ALERT SERVICE (FCM)
 * Handles HIGH-RISK push notification delivery, secure event processing, and replay protection.
 * 
 * SECURITY & ARCHITECTURAL DECISIONS:
 * 1. Data-Only Payload: Ensures onMessageReceived is triggered regardless of app state (foreground/background/killed).
 * 2. Replay Protection: Prevents duplicate notification processing via eventId cache.
 * 3. Freshness Check: Rejects notifications older than 5 minutes to prevent stale alert replay.
 * 4. High-Priority Channel: Urgent vibration and red styling for scam detection awareness.
 * 5. Device Binding: Syncs FCM tokens only for authenticated users to prevent data leakage.
 */
class AlertService : FirebaseMessagingService() {

    private val job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + job)

    companion object {
        private const val TAG = "AlertService"
        private const val CHANNEL_HIGH_RISK = "high_risk_alerts"
        private const val MAX_EVENT_AGE_MS = 300_000 // 5 minutes
        
        // In-memory replay protection cache
        private val processedEvents = ConcurrentHashMap.newKeySet<String>()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM Token: $token")
        
        // Securely update token in Firebase if user is authenticated
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            serviceScope.launch {
                FirebaseManager.updateFcmToken(uid, token)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        val data = remoteMessage.data
        if (data.isEmpty()) return

        // 1. DATA EXTRACTION
        val eventId = data["eventId"] ?: return
        val riskLevel = data["riskLevel"] ?: "LOW"
        val timestampStr = data["timestamp"] ?: "0"
        val timestamp = timestampStr.toLongOrNull() ?: 0L

        // 2. SECURITY: REPLAY & FRESHNESS VALIDATION
        if (processedEvents.contains(eventId)) {
            Log.w(TAG, "Duplicate event ignored: $eventId")
            return
        }
        
        if (System.currentTimeMillis() - timestamp > MAX_EVENT_AGE_MS) {
            Log.e(TAG, "Stale event rejected: $eventId")
            return
        }

        // 3. INTEGRITY VALIDATION (STUB)
        if (!validatePayloadIntegrity(data)) {
            Log.e(TAG, "Payload integrity check failed for: $eventId")
            return
        }

        // 4. PROCESS EVENT
        processedEvents.add(eventId)
        if (riskLevel == "HIGH") {
            showHighRiskNotification(data)
        }
    }

    private fun showHighRiskNotification(data: Map<String, String>) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(notificationManager)

        val eventId = data["eventId"]
        val callerNumber = data["callerNumber"] ?: "Unknown"

        // Deep Link into Guardian Dashboard
        val intent = Intent(this, GuardianDashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("eventId", eventId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, eventId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_HIGH_RISK)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with app icon
            .setContentTitle("⚠️ HIGH RISK SCAM DETECTED")
            .setContentText("Scam call from $callerNumber. View details immediately.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setColor(Color.RED)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setPublicVersion(NotificationCompat.Builder(this, CHANNEL_HIGH_RISK).setContentTitle("Security Alert").build())
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(eventId.hashCode(), notification)
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_HIGH_RISK,
                "High Risk Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts for potential cyber fraud"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun validatePayloadIntegrity(data: Map<String, String>): Boolean {
        // PRODUCTION: Verify HMAC-SHA256 of payload against a shared secret or public key
        // For now, ensure critical fields exist and are non-empty
        return data["eventId"] != null && data["callerNumber"] != null
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
