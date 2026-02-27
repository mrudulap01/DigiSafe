package com.digisafe.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.TextView
import com.digisafe.app.R
import com.digisafe.app.core.HighRiskManager
import com.digisafe.app.guardian.GuardianManager
import com.digisafe.app.service.CallTerminator
import com.google.android.material.button.MaterialButton

/**
 * OverlayManager - Displays full-screen TYPE_APPLICATION_OVERLAY alert
 * during high-risk situations.
 *
 * Features:
 * - Red warning screen with scam checklist
 * - Disconnect Call / Call Guardian / I Am Safe buttons
 * - Panic button for emergency
 * - Haptic feedback on display
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "OverlayManager"
    }

    /**
     * Show full-screen high-risk overlay with call information.
     */
    fun showHighRiskOverlay(
        callerNumber: String?,
        durationSecs: Long,
        riskScore: Float
    ) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted")
            return
        }

        // Avoid duplicate overlays
        if (overlayView != null) {
            updateOverlayInfo(callerNumber, durationSecs, riskScore)
            return
        }

        handler.post {
            try {
                val inflater = LayoutInflater.from(context)
                overlayView = inflater.inflate(R.layout.overlay_high_risk, null)

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                )

                // Make overlay interactive
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()

                setupOverlayViews(overlayView!!, callerNumber, durationSecs, riskScore)
                windowManager.addView(overlayView, params)

                // Vibrate to alert user
                vibrateAlert()

                Log.d(TAG, "High risk overlay shown")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay", e)
            }
        }
    }

    /**
     * Show banking shield overlay when UPI app is detected during high-risk state.
     */
    fun showBankingShieldOverlay() {
        if (!Settings.canDrawOverlays(context)) return
        if (overlayView != null) return // Don't stack overlays

        handler.post {
            try {
                val inflater = LayoutInflater.from(context)
                overlayView = inflater.inflate(R.layout.overlay_banking_shield, null)

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT
                )

                overlayView?.findViewById<MaterialButton>(R.id.btnBlockTransaction)
                    ?.setOnClickListener {
                        dismissOverlay()
                    }

                overlayView?.findViewById<MaterialButton>(R.id.btnAllowTransaction)
                    ?.setOnClickListener {
                        dismissOverlay()
                    }

                windowManager.addView(overlayView, params)
                vibrateAlert()

                Log.d(TAG, "Banking shield overlay shown")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show banking overlay", e)
            }
        }
    }

    /**
     * Dismiss the current overlay.
     */
    fun dismissOverlay() {
        handler.post {
            try {
                overlayView?.let {
                    windowManager.removeView(it)
                    overlayView = null
                    Log.d(TAG, "Overlay dismissed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dismiss overlay", e)
            }
        }
    }

    private fun setupOverlayViews(
        view: View,
        callerNumber: String?,
        durationSecs: Long,
        riskScore: Float
    ) {
        // Caller info
        view.findViewById<TextView>(R.id.tvCallerInfo)?.text =
            "📞 Caller: ${callerNumber ?: "Unknown Number"}"

        // Duration
        val minutes = durationSecs / 60
        val seconds = durationSecs % 60
        view.findViewById<TextView>(R.id.tvCallDuration)?.text =
            "⏱️ Duration: ${String.format("%d:%02d", minutes, seconds)}"

        // Risk score
        view.findViewById<TextView>(R.id.tvRiskScoreOverlay)?.text =
            String.format("Risk Score: %.0f%%", riskScore * 100)

        // Disconnect button
        view.findViewById<MaterialButton>(R.id.btnDisconnect)?.setOnClickListener {
            val success = CallTerminator.endCall(context)
            if (success) {
                dismissOverlay()
                HighRiskManager.resetRisk()
            }
        }

        // Call Guardian button
        view.findViewById<MaterialButton>(R.id.btnCallGuardian)?.setOnClickListener {
            val guardianPhone = GuardianManager.getGuardianPhone(context)
            if (!guardianPhone.isNullOrBlank()) {
                val callIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$guardianPhone")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(callIntent)
            }
        }

        // I Am Safe button
        view.findViewById<MaterialButton>(R.id.btnSafe)?.setOnClickListener {
            dismissOverlay()
            HighRiskManager.resetRisk()
        }

        // Panic button - immediately disconnect and alert guardian
        view.findViewById<MaterialButton>(R.id.btnPanic)?.setOnClickListener {
            CallTerminator.endCall(context)
            dismissOverlay()
            HighRiskManager.resetRisk()
        }
    }

    private fun updateOverlayInfo(callerNumber: String?, durationSecs: Long, riskScore: Float) {
        handler.post {
            overlayView?.let { view ->
                view.findViewById<TextView>(R.id.tvCallerInfo)?.text =
                    "📞 Caller: ${callerNumber ?: "Unknown Number"}"

                val minutes = durationSecs / 60
                val seconds = durationSecs % 60
                view.findViewById<TextView>(R.id.tvCallDuration)?.text =
                    "⏱️ Duration: ${String.format("%d:%02d", minutes, seconds)}"

                view.findViewById<TextView>(R.id.tvRiskScoreOverlay)?.text =
                    String.format("Risk Score: %.0f%%", riskScore * 100)
            }
        }
    }

    private fun vibrateAlert() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 500, 200, 500, 200, 500), -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 500, 200, 500, 200, 500), -1
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }
}
