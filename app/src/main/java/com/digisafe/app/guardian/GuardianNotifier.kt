package com.digisafe.app.guardian

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.digisafe.app.DigiSafeApp
import com.digisafe.app.R
import com.digisafe.app.ui.MainActivity

/**
 * GuardianNotifier — Sends real-time alerts to the guardian contact
 * when a high-risk scam call is detected.
 *
 * Primary: SMS with caller details, duration, and risk score.
 * Fallback: Local high-priority notification if SMS permission is missing.
 */
class GuardianNotifier(private val context: Context) {

    companion object {
        private const val TAG = "GuardianNotifier"
        private const val ALERT_NOTIFICATION_ID = 2001
    }

    /**
     * Send a high-risk alert to the guardian.
     * Tries SMS first, always posts a local notification as well.
     */
    fun sendAlert(callerNumber: String?, durationSecs: Long, riskScore: Float) {
        val guardianPhone = GuardianManager.getGuardianPhone(context)
        val guardianName = GuardianManager.getGuardianName(context)

        val durationFormatted = formatDuration(durationSecs)
        val callerDisplay = callerNumber ?: "Unknown Number"

        // Try SMS alert
        if (!guardianPhone.isNullOrBlank()) {
            sendSmsAlert(guardianPhone, callerDisplay, durationFormatted, riskScore)
        } else {
            Log.w(TAG, "No guardian phone set — skipping SMS alert")
        }

        // Always post local notification
        postLocalNotification(callerDisplay, durationFormatted, riskScore, guardianName)
    }

    private fun sendSmsAlert(
        guardianPhone: String,
        callerDisplay: String,
        durationFormatted: String,
        riskScore: Float
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "SEND_SMS permission not granted — falling back to notification only")
            return
        }

        try {
            val message = context.getString(
                R.string.sms_alert,
                callerDisplay,
                durationFormatted,
                riskScore * 100
            )

            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(
                guardianPhone,
                null,
                parts,
                null,
                null
            )

            Log.d(TAG, "SMS alert sent to $guardianPhone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS alert", e)
        }
    }

    private fun postLocalNotification(
        callerDisplay: String,
        durationFormatted: String,
        riskScore: Float,
        guardianName: String?
    ) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val title = "🚨 HIGH RISK — Possible Scam Detected!"
            val body = buildString {
                append("Caller: $callerDisplay\n")
                append("Duration: $durationFormatted\n")
                append("Risk Score: ${String.format("%.0f%%", riskScore * 100)}\n")
                if (!guardianName.isNullOrBlank()) {
                    append("Guardian ($guardianName) has been notified.")
                }
            }

            val notification = NotificationCompat.Builder(context, DigiSafeApp.ALERT_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("Caller: $callerDisplay — Risk: ${String.format("%.0f%%", riskScore * 100)}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
                .build()

            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(ALERT_NOTIFICATION_ID, notification)
            }

            Log.d(TAG, "Local alert notification posted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification", e)
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
