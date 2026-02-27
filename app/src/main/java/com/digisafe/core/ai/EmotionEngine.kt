package core.ai

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


class EmotionEngine(private val context: Context) {

    private lateinit var interpreter: Interpreter

    init {
        try {
            // Load the model once during initialization
            val modelBuffer = loadModelFile()
            interpreter = Interpreter(modelBuffer)
            Log.d("EmotionEngine", "TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.e("DigiSafe-AI", "TFLite model loading failed", e)
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun analyzeAudio(mfcc: FloatArray): Float {
        return try {
            val input = arrayOf(mfcc)
            val output = Array(1) { FloatArray(1) }

            interpreter.run(input, output)

            val score = output[0][0]
            Log.d("DigiSafe-AI", "Emotion Score: $score")
            score

        } catch (e: Exception) {
            Log.e("DigiSafe-AI", "Inference failed", e)
            0.1f
        }
    }
}