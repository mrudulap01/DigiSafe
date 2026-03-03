package com.digisafe.guardian.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.digisafe.guardian.MainActivity
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * AlertService: Handles incoming high-risk call notifications.
 * Implements Replay Protection and high-priority notification delivery.
 */
class AlertService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AlertService"
        private const val CHANNEL_ID = "high_risk_alerts"
        private const val PREFS_NAME = "alert_prefs"
        private const val KEY_LAST_EVENT_ID = "last_event_id"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "onMessageReceived: From=${remoteMessage.from}")

        Log.d("FCM_VERIFY", "Message received")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "FCM RECEIVED", Toast.LENGTH_LONG).show()
        }

        // TEMP VERIFICATION BLOCK
        val testAlert = mapOf(
            "state" to "PENDING",
            "timestamp" to ServerValue.TIMESTAMP
        )
        FirebaseDatabase.getInstance().getReference("users/test_user/alerts/test_event")
            .setValue(testAlert)
        // END TEMP VERIFICATION BLOCK
        
        // DEBUG: Log if there's a notification object (handled by OS if app in background)
        remoteMessage.notification?.let {
            Log.d(TAG, "Notification received: Title=${it.title}, Body=${it.body}")
        }

        // 1. Extract Data
        val data = remoteMessage.data
        Log.d(TAG, "Data payload: $data")
        
        if (data.isEmpty()) {
            Log.w(TAG, "Message received but data payload is EMPTY")
            return
        }

        val eventId = data["eventId"] ?: "test_${System.currentTimeMillis()}"
        val riskLevel = data["riskLevel"] ?: "UNKNOWN"
        val callerNumber = data["callerNumber"] ?: "Hidden"

        Log.d(TAG, "Processing Alert: eventId=$eventId, risk=$riskLevel")

        // 2. Replay Protection (SKIP for DEBUG if eventId starts with 'test_')
        if (!eventId.startsWith("test_") && isDuplicateEvent(eventId)) {
            Log.w(TAG, "Duplicate event detected. Ignoring eventId=$eventId")
            return
        }

        // 3. Show Notification
        showNotification(eventId, callerNumber, riskLevel)
    }

    override fun onNewToken(token: String) {
        Log.d("FCM_TOKEN", "onNewToken triggered: $token")
    }

    private fun isDuplicateEvent(eventId: String): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastId = prefs.getString(KEY_LAST_EVENT_ID, null)
        
        if (lastId == eventId) return true
        
        prefs.edit().putString(KEY_LAST_EVENT_ID, eventId).apply()
        return false
    }

    private fun showNotification(eventId: String, callerNumber: String, riskLevel: String) {
        Log.d(TAG, "Showing notification for $eventId")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create Channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "High Risk Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent notifications for detected scam calls"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("eventId", eventId)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("High Risk Call Detected")
            .setContentText("Caller: $callerNumber")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("High Risk Call Detected\nCaller: $callerNumber\nRisk Level: $riskLevel\nEvent ID: $eventId"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            notificationManager.notify(eventId.hashCode(), notificationBuilder.build())
            Log.d(TAG, "Notification post successful")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification", e)
        }
    }
}
