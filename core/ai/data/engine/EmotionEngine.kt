package core.ai.data.engine

import android.content.res.AssetFileDescriptor
import android.util.Log
import core.ai.data.processor.MFCCProcessor
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Analyzes audio and behavioral data to determine the user's emotional state.
 */
class EmotionEngine(
    private val mfccProcessor: MFCCProcessor
) {

    private var assetFileDescriptor: AssetFileDescriptor? = null
    
    // Double-checked locking for lazy thread-safe initialization of the Interpreter
    @Volatile
    private var _interpreter: Interpreter? = null
    private val lock = Any()

    /**
     * Prepares the engine with the model file descriptor safely.
     * The Interpreter itself remains lazy-initialized.
     */
    fun initialize(assetFileDescriptor: AssetFileDescriptor?) {
        synchronized(lock) {
            this.assetFileDescriptor = assetFileDescriptor
        }
    }

    private fun getLazyInterpreter(): Interpreter? {
        var interpreter = _interpreter
        if (interpreter != null) return interpreter

        return synchronized(lock) {
            val tflite = _interpreter
            if (tflite != null) {
                tflite
            } else {
                val descriptor = assetFileDescriptor ?: run {
                    Log.e("EmotionEngine", "AssetFileDescriptor is null. Cannot initialize Interpreter.")
                    return@synchronized null
                }
                try {
                    val modelBuffer = loadModelFile(descriptor)
                    val newInterpreter = Interpreter(modelBuffer, Interpreter.Options())
                    _interpreter = newInterpreter
                    newInterpreter
                } catch (e: Exception) {
                    Log.e("EmotionEngine", "Error initializing Interpreter: \${e.message}", e)
                    null
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun loadModelFile(descriptor: AssetFileDescriptor): MappedByteBuffer {
        FileInputStream(descriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength
            )
        }
    }

    /**
     * Analyzes the given input and returns an emotion score.
     */
    fun analyzeEmotion(audioData: ByteArray): Float {
        // TODO: Implement emotion analysis logic using MFCC features
        return 0.0f
    }
    
    /**
     * Runs inference on the given input, detecting shapes dynamically and handling errors gracefully.
     */
    fun runInference(input: FloatArray): FloatArray {
        val tflite = getLazyInterpreter() ?: return FloatArray(0)

        // Ensure thread safety during inference
        return synchronized(lock) {
            try {
                // Dynamically obtain output tensor info
                val outputTensor = tflite.getOutputTensor(0)
                val outputShape = outputTensor.shape()
                
                // Calculate required output buffer size
                var outputElementCount = 1
                for (dim in outputShape) {
                    if (dim > 0) outputElementCount *= dim
                }
                
                val inputBuffer = java.nio.FloatBuffer.wrap(input)
                val outputBuffer = java.nio.FloatBuffer.allocate(outputElementCount)
                
                // Execute TFLite model inference
                tflite.run(inputBuffer, outputBuffer)
                
                outputBuffer.array()
            } catch (e: Exception) {
                Log.e("EmotionEngine", "Error during inference: \${e.message}", e)
                FloatArray(0)
            }
        }
    }

    /**
     * Cleans up resources.
     */
    fun release() {
        synchronized(lock) {
            try {
                _interpreter?.close()
                _interpreter = null
                assetFileDescriptor = null
            } catch (e: Exception) {
                Log.e("EmotionEngine", "Error closing interpreter: \${e.message}", e)
            }
        }
    }
}
