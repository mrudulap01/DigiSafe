package com.digisafe.core.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

class CallMonitorService : Service() {

    private val channelId = "digisafechannel"

    private var audioChunkListener: AudioChunkListener? = null

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var phoneStateListener: PhoneStateListener

    private var callStartTime: Long = 0
    private var isCallActive = false
    private var isRecording = false
    private var currentPhoneNumber: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var delayedRecordingRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()

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

    fun setAudioChunkListener(listener: AudioChunkListener) {
        this.audioChunkListener = listener
    }

    private fun setupPhoneStateListener() {

        phoneStateListener = object : PhoneStateListener() {

            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)

                when (state) {

                    TelephonyManager.CALL_STATE_RINGING -> {
                        Log.d("DigiSafe", "Call RINGING")
                        currentPhoneNumber = phoneNumber
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.d("DigiSafe", "Call OFFHOOK")

                        callStartTime = System.currentTimeMillis()
                        isCallActive = true

                        delayedRecordingRunnable = Runnable {

                            if (isCallActive && !isRecording) {

                                Log.d("DigiSafe", "Starting 5-sec recording")

                                isRecording = true
                                audioRecorder.startRecording()

                                handler.postDelayed({

                                    val path = audioRecorder.stopRecording()
                                    isRecording = false

                                    if (path != null) {

                                        val duration =
                                            (System.currentTimeMillis() - callStartTime) / 1000

                                        Log.d("DigiSafe", "Recording saved: $path")

                                        audioChunkListener?.onAudioChunkReady(
                                            filePath = path,
                                            phoneNumber = currentPhoneNumber,
                                            duration = duration
                                        )
                                    }

                                }, 5000)
                            }
                        }

                        handler.postDelayed(delayedRecordingRunnable!!, 60000)
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {

                        if (!isCallActive) return

                        Log.d("DigiSafe", "Call IDLE")

                        delayedRecordingRunnable?.let {
                            handler.removeCallbacks(it)
                        }

                        isCallActive = false

                        if (isRecording) {
                            audioRecorder.stopRecording()
                            isRecording = false
                            Log.d("DigiSafe", "Recording stopped due to call end")
                        }

                        val duration =
                            (System.currentTimeMillis() - callStartTime) / 1000

                        Log.d("DigiSafe", "Call duration: $duration seconds")

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

    override fun onDestroy() {
        super.onDestroy()

        telephonyManager.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_NONE
        )

        handler.removeCallbacksAndMessages(null)

        isCallActive = false
        isRecording = false

        Log.d("DigiSafe", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

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