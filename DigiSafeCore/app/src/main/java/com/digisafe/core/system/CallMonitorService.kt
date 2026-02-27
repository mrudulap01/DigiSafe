package com.digisafe.core.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

import android.os.Handler
import android.os.Looper
import java.io.File

class CallMonitorService : Service() {

    private val TAG = "CallMonitorService"
    private var telephonyManager: TelephonyManager? = null
    private val CHANNEL_ID = "digisafe_channel"
    private lateinit var audioRecorder: AudioRecorder
    private var isRecording = false
    private var isCallActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val DEBUG_MODE = true

    private val phoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    Log.d(TAG, "Call State: RINGING. Incoming number: $phoneNumber")
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    Log.d(TAG, "Call State: OFFHOOK.")
                    isCallActive = true
                    handler.postDelayed({
                        if (isCallActive && !isRecording) {
                            Log.d(TAG, "Starting delayed recording...")
                            audioRecorder.startRecording()
                            isRecording = true
                            
                            // Stop recording after 5000 ms
                            handler.postDelayed({
                                if (isRecording) {
                                    val filePath = audioRecorder.stopRecording()
                                    isRecording = false
                                    if (filePath != null) {
                                        val file = File(filePath)
                                        val fileSize = file.length()
                                        Log.d(TAG, "Recording stopped automatically after 5000ms. File path: $filePath, Size: $fileSize bytes")
                                        if (fileSize > 1000) {
                                            Log.d(TAG, "Recording successful: Size is > 1000 bytes.")
                                        } else {
                                            Log.w(TAG, "Recording might have failed: Size is <= 1000 bytes.")
                                        }
                                    } else {
                                        Log.e(TAG, "Recording failed, file path is null.")
                                    }
                                }
                            }, 5000)
                        }
                    }, 60000)
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    Log.d(TAG, "Call State: IDLE.")
                    isCallActive = false
                    handler.removeCallbacksAndMessages(null)
                    if (isRecording) {
                        Log.d(TAG, "Recording stopped due to call end")
                        val filePath = audioRecorder.stopRecording()
                        isRecording = false
                        if (filePath != null) {
                            val file = File(filePath)
                            val fileSize = file.length()
                            Log.d(TAG, "Recording stopped on call end. File path: $filePath, Size: $fileSize bytes")
                            if (fileSize > 1000) {
                                Log.d(TAG, "Recording successful: Size is > 1000 bytes.")
                            } else {
                                Log.w(TAG, "Recording might have failed: Size is <= 1000 bytes.")
                            }
                        } else {
                            Log.e(TAG, "Recording failed on call end, file path is null.")
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        createNotificationChannel()
        startForeground(1, createNotification())
        
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        
        audioRecorder = AudioRecorder(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service Started")

        if (DEBUG_MODE) {
            Log.d(TAG, "DEBUG_MODE enabled: Starting test recording...")
            handler.post {
                if (!isRecording) {
                    audioRecorder.startRecording()
                    isRecording = true

                    handler.postDelayed({
                        if (isRecording) {
                            val filePath = audioRecorder.stopRecording()
                            isRecording = false
                            if (filePath != null) {
                                val file = File(filePath)
                                val fileSize = file.length()
                                Log.d(TAG, "Test recording complete. File path: $filePath, Size: $fileSize bytes")
                                if (fileSize > 1000) {
                                    Log.d(TAG, "Recording successful: Size is > 1000 bytes.")
                                } else {
                                    Log.w(TAG, "Recording might have failed: Size is <= 1000 bytes.")
                                }
                            } else {
                                Log.e(TAG, "Test recording failed, file path is null.")
                            }
                        }
                    }, 5000)
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service Destroyed")
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "DigiSafe Monitoring"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DigiSafe Active")
            .setContentText("Monitoring Calls")
            // .setSmallIcon(R.drawable.ic_launcher_foreground) // Add your app's icon here
            .build()
    }
}
