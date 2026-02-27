package com.digisafe.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * PhoneStateReceiver - Broadcast receiver for PHONE_STATE changes.
 * Ensures CallMonitorService is running when calls occur.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            Log.d(TAG, "Phone state changed: $state")

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING,
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // Ensure call monitor service is running
                    try {
                        CallMonitorService.start(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start CallMonitorService", e)
                    }
                }
            }
        }
    }
}
