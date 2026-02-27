package com.digisafe.app.service

import android.content.Context
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log

/**
 * CallTerminator - Handles ending active calls via TelecomManager.
 *
 * Uses TelecomManager.endCall() which requires the app to hold
 * the ANSWER_PHONE_CALLS permission. Falls back to overlay lock
 * if termination fails.
 *
 * Limitations:
 * - GSM calls only (won't work for WhatsApp/VoIP)
 * - Requires user approval for ANSWER_PHONE_CALLS permission
 */
object CallTerminator {

    private const val TAG = "CallTerminator"

    /**
     * Attempt to end the current active call.
     * Returns true if the call was successfully terminated.
     */
    @Suppress("DEPRECATION")
    fun endCall(context: Context): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 9+ - endCall() requires ANSWER_PHONE_CALLS permission
                val success = telecomManager.endCall()
                Log.d(TAG, "endCall() result: $success")
                success
            } else {
                // Older Android - use TelephonyManager via reflection
                try {
                    val telephonyService = context.getSystemService(Context.TELEPHONY_SERVICE)
                    val clazz = Class.forName(telephonyService.javaClass.name)
                    val method = clazz.getDeclaredMethod("endCall")
                    method.isAccessible = true
                    method.invoke(telephonyService) as Boolean
                } catch (e: Exception) {
                    Log.e(TAG, "Reflection-based endCall failed", e)
                    false
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for endCall()", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end call", e)
            false
        }
    }
}
