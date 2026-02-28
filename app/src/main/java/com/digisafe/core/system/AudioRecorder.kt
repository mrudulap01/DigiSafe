package com.digisafe.core.system

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFilePath: String = ""

    fun startRecording() {

        val file = File(context.getExternalFilesDir(null), "temp_record.mp4")
        outputFilePath = file.absolutePath

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        mediaRecorder?.apply {

            setAudioSource(MediaRecorder.AudioSource.MIC)

            // MP4 container
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            // AAC encoder (modern & playable)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)

            setOutputFile(outputFilePath)

            prepare()
            start()

            Log.d("DigiSafe", "Recording started")
        }
    }

    fun stopRecording(): String {

        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }

            Log.d("DigiSafe", "Recording stopped")

        } catch (e: Exception) {
            Log.e("DigiSafe", "Stop error: ${e.message}")
        }

        mediaRecorder = null

        return outputFilePath
    }
}