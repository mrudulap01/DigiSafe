package com.digisafe.core.system

import core.ai.AiCoordinator
import core.ai.RiskCallback
import core.ai.RiskData
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.digisafe.core.ui.HighRiskActivity

class CallMonitorService : Service(), RiskCallback {

    private val channelId = "digisafechannel"
    private val guardianNumber = "7038343834"

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var phoneStateListener: PhoneStateListener
    private lateinit var aiCoordinator: AiCoordinator

    private var callStartTime: Long = 0
    private var isCallActive = false
    private var isRecording = false
    private var currentPhoneNumber: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var delayedRecordingRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()

        aiCoordinator = AiCoordinator(this, this)   // ✅ FIXED (only one parameter)
        audioRecorder = AudioRecorder(this)

        createNotificationChannel()

        telephonyManager =
            getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        setupPhoneStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("DigiSafe Active")
            .setContentText("Monitoring Calls")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)

        return START_STICKY
    }

    private fun sendGuardianAlert(phone: String?) {

        val message = """
⚠ DigiSafe Alert!
High risk scam call detected.
Caller: $phone
Call terminated for safety.
    """.trimIndent()

        val smsIntent = Intent(Intent.ACTION_SENDTO)
        smsIntent.data = android.net.Uri.parse("smsto:$guardianNumber")
        smsIntent.putExtra("sms_body", message)
        smsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            startActivity(smsIntent)
            Log.d("DigiSafe-Guardian", "Opened SMS app for guardian alert")
        } catch (e: Exception) {
            Log.e("DigiSafe-Guardian", "Failed to open SMS app: ${e.message}")
        }
    }
    private fun setupPhoneStateListener() {

        phoneStateListener = object : PhoneStateListener() {

            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)

                when (state) {

                    TelephonyManager.CALL_STATE_RINGING -> {
                        currentPhoneNumber = phoneNumber
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {

                        callStartTime = System.currentTimeMillis()
                        isCallActive = true

                        delayedRecordingRunnable = Runnable {

                            if (isCallActive && !isRecording) {

                                isRecording = true
                                audioRecorder.startRecording()

                                handler.postDelayed({

                                    val path = audioRecorder.stopRecording()
                                    isRecording = false

                                    if (path != null) {

                                        val durationSeconds =
                                            (System.currentTimeMillis() - callStartTime) / 1000

                                        aiCoordinator.processAudioChunk(
                                            filePath = path,
                                            duration = durationSeconds,
                                            phoneNumber = currentPhoneNumber ?: "Unknown",
                                            transcript = "police arrest legal action transfer now confidential do not disconnect"
                                        )
                                    }

                                }, 5000)
                            }
                        }

                        handler.postDelayed(delayedRecordingRunnable!!, 60000)
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {

                        if (!isCallActive) return

                        delayedRecordingRunnable?.let {
                            handler.removeCallbacks(it)
                        }

                        isCallActive = false

                        if (isRecording) {
                            audioRecorder.stopRecording()
                            isRecording = false
                        }

                        currentPhoneNumber = null
                    }
                }
            }
        }

        telephonyManager.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_CALL_STATE
        )
    }

    override fun onHighRiskDetected(riskData: RiskData) {

        Log.d("DigiSafe-Integration",
            "HIGH RISK DETECTED for ${riskData.phoneNumber}")

        // 🔴 Launch UI
        val intent = Intent(this, HighRiskActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)

        // 🔴 Terminate Call
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

                if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED) {

                    val telecomManager =
                        getSystemService(Context.TELECOM_SERVICE) as TelecomManager

                    telecomManager.endCall()
                }
            }
        } catch (e: Exception) {
            Log.e("DigiSafe-Integration", "Termination failed: ${e.message}")
        }

        // 🔴 Send Guardian SMS
        sendGuardianAlert(riskData.phoneNumber)

        // 🔴 Show Notification
        showHighRiskNotification(riskData.phoneNumber)
    }

    private fun showHighRiskNotification(phone: String?) {

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚠ High Risk Call Detected")
            .setContentText("Guardian alerted. Suspicious call from $phone blocked.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(2, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        telephonyManager.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_NONE
        )
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "DigiSafe Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager =
                getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}