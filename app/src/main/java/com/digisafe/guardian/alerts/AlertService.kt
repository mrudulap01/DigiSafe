package com.digisafe.guardian.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.digisafe.guardian.R
import com.digisafe.guardian.backend.FirebaseManager
import com.digisafe.guardian.dashboard.GuardianDashboardActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AlertService: Production-grade FCM listener for DIGISAFE.
 * Responsible for high-priority alert delivery, replay protection, and device binding.
 */
class AlertService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processedEvents = mutableSetOf<String>() // Simple in-memory de-duplication

    companion object {
        private const val TAG = "AlertService"
        private const val CHANNEL_HIGH_RISK = "high_risk_alerts"
        private const val REPLAY_WINDOW_MS = 300_000L // 5 minutes
    }

    /**
     * Called when a new FCM token is generated.
     * Binding the token to the user ID is critical for targeted alert routing.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New token generated: $token")
        
        serviceScope.launch {
            val userId = getEncryptedUserId()
            if (userId != null) {
                FirebaseManager.updateFcmToken(userId, token)
            }
        }
    }

    /**
     * Primary entry point for incoming high-risk alert payloads.
     * We ONLY use data payloads to ensure the app can process the notification 
     * even when killed or in the background.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data.isEmpty()) return

        val eventId = data["eventId"] ?: return
        val riskLevel = data["riskLevel"] ?: "UNKNOWN"
        val timestamp = data["timestamp"]?.toLongOrNull() ?: 0L

        // 1. Replay Protection: Ignore duplicate or stale events
        if (isReplay(eventId, timestamp)) {
            Log.w(TAG, "Replay attempt detected for event: $eventId")
            return
        }

        // 2. Security: Validate risk level to prevent spoofing noise
        if (riskLevel != "HIGH") {
            Log.d(TAG, "Low risk event received, skipping high-priority alert.")
            return
        }

        // 3. Process Alert
        processedEvents.add(eventId)
        showHighRiskNotification(data)
    }

    /**
     * Builds and displays a HIGH priority notification.
     * Includes red accent, custom vibration, and deep link to dashboard.
     */
    private fun showHighRiskNotification(data: Map<String, String>) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(notificationManager)

        val callerNumber = data["callerNumber"] ?: "Unknown Number"
        val eventId = data["eventId"] ?: ""

        // Deep Link Intent
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
                description = "Critical alerts for senior citizen fraud detection"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Determines if the event is a replay or stale.
     * SECURITY: Replay window prevents old notifications from being re-triggered.
     */
    private fun isReplay(eventId: String, timestamp: Long): Boolean {
        if (processedEvents.contains(eventId)) return true
        val now = System.currentTimeMillis()
        return (now - timestamp) > REPLAY_WINDOW_MS
    }

    private fun getEncryptedUserId(): String? {
        return try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                this,
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

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            // Cleanup old events from memory (if needed, but service should be short-lived)
        }
    }
}
