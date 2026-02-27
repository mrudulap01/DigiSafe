package com.digisafe.app.service

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log

/**
 * CallTerminator — Handles ending active calls via TelecomManager.
 *
 * Features:
 * - TelecomManager.endCall() for Android 9+
 * - RoleManager default dialer request for enhanced termination
 * - Fail-safe: returns false if termination fails, so caller can
 *   trigger lock-screen overlay as fallback
 *
 * Limitations:
 * - GSM cellular calls only (won't work for WhatsApp/VoIP)
 * - Requires ANSWER_PHONE_CALLS permission
 * - RoleManager approval is user-initiated
 */
object CallTerminator {

    private const val TAG = "CallTerminator"
    const val REQUEST_CODE_DEFAULT_DIALER = 3001

    /**
     * Attempt to end the current active call.
     * Returns true if the call was successfully terminated.
     * If false, the caller should trigger fail-safe lock overlay.
     */
    @Suppress("DEPRECATION")
    fun endCall(context: Context): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val success = telecomManager.endCall()
                Log.d(TAG, "endCall() result: $success")
                if (!success) {
                    Log.w(TAG, "endCall() failed — fail-safe overlay should be triggered")
                }
                success
            } else {
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

    /**
     * Attempt to end call with fail-safe: if termination fails,
     * triggers the lock-screen overlay via the provided callback.
     */
    fun endCallWithFailSafe(context: Context, onFailSafe: () -> Unit) {
        val success = endCall(context)
        if (!success) {
            Log.w(TAG, "Call termination failed — triggering fail-safe lock overlay")
            onFailSafe()
        }
    }

    /**
     * Request the default dialer role via RoleManager (Android 10+).
     * This gives the app enhanced call control capabilities.
     * Must be called from an Activity.
     */
    fun requestDefaultDialerRole(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQUEST_CODE_DEFAULT_DIALER)
                Log.d(TAG, "Requested default dialer role")
            } else {
                Log.d(TAG, "Default dialer role already held or not available")
            }
        } else {
            // Pre-Android 10: use TelecomManager
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(
                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    activity.packageName
                )
            }
            activity.startActivity(intent)
            Log.d(TAG, "Requested default dialer via TelecomManager intent")
        }
    }

    /**
     * Check if this app is currently the default dialer.
     */
    fun isDefaultDialer(context: Context): Boolean {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return telecomManager.defaultDialerPackage == context.packageName
    }
}
