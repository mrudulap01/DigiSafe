package com.digisafe.core.ai

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class EmotionEngine(private val context: Context, private val mfccProcessor: MFCCProcessor = MFCCProcessor()) {

    private var interpreter: Interpreter? = null

    init {
        try {
            interpreter = Interpreter(loadModelFile("emotion_model.tflite"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun analyzeAudio(filePath: String): Float {
        // Use MFCCProcessor to convert the PCM audio file to TFLite input feature array
        val input = mfccProcessor.processAudioToMFCC(filePath)
        
        // Mock output float array (e.g., single float representing aggression score)
        val output = Array(1) { FloatArray(1) }

        try {
            interpreter?.run(input, output)
            val score = output[0][0]
            Log.d("DigiSafe-AI", "EmotionEngine - Analyzed audio score: $score")
            return score
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback random mock value if inference fails (e.g., model is missing)
            return 0.5f 
        }
    }

    fun close() {
        interpreter?.close()
    }
}
