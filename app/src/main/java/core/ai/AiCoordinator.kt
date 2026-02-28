package core.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AiCoordinator(
    private val context: Context,
    private val riskCallback: RiskCallback
) {

    private val mfccProcessor = MFCCProcessor()
    private val emotionEngine = EmotionEngine(context)
    private val authorityDetector = AuthorityDetector()
    private val riskEngine = RiskEngine()

    fun processAudioChunk(
        filePath: String,
        duration: Long,
        phoneNumber: String,
        transcript: String?
    ) {
        if (filePath.isEmpty() || !java.io.File(filePath).exists()) {
            Log.w("DigiSafe-AI", "File path is empty or file does not exist: $filePath")
            return
        }

        val mfcc = mfccProcessor.processAudio(filePath)
        val emotionScore = emotionEngine.analyzeAudio(mfcc)
        val authorityScore = authorityDetector.calculateAuthorityScore(transcript)

        val riskLevel = riskEngine.calculateRisk(
            duration,
            emotionScore,
            authorityScore
        )

        val riskData = RiskData(
            phoneNumber = phoneNumber,
            duration = duration,
            emotionScore = emotionScore,
            authorityScore = authorityScore,
            riskLevel = riskLevel,
            timestamp = System.currentTimeMillis()
        )

        if (riskLevel == "HIGH") {
            Log.d("DigiSafe-AI", "HIGH risk detected. Triggering callback.")
            riskCallback.onHighRiskDetected(riskData)
        }
    }
}