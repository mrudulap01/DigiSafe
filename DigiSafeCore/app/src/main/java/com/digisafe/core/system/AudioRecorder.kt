package com.digisafe.core.system

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.IOException

class AudioRecorder(private val context: Context) {

    private val TAG = "AudioRecorder"
    private var mediaRecorder: MediaRecorder? = null
    private val outputFilePath: String = context.filesDir.absolutePath + "/temp_record.3gp"

    @Suppress("DEPRECATION")
    fun startRecording() {
        Log.d(TAG, "Attempting to start recording...")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }.apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(outputFilePath)

                prepare()
                start()
                Log.d(TAG, "Recording started successfully. Saved at: $outputFilePath")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException starting MediaRecorder", e)
                releaseRecorder()
            } catch (e: IOException) {
                Log.e(TAG, "IOException starting MediaRecorder", e)
                releaseRecorder()
            } catch (e: Exception) {
                Log.e(TAG, "Unknown Exception starting MediaRecorder", e)
                releaseRecorder()
            }
        }
    }

    fun stopRecording(): String? {
        Log.d(TAG, "Attempting to stop recording...")
        return try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            mediaRecorder = null
            Log.d(TAG, "Recording stopped successfully. File preserved at: $outputFilePath")
            outputFilePath
        } catch (e: Exception) {
            Log.e(TAG, "Exception while stopping MediaRecorder", e)
            mediaRecorder = null
            null
        }
    }

    private fun releaseRecorder() {
        mediaRecorder?.apply {
            release()
        }
        mediaRecorder = null
    }
}
