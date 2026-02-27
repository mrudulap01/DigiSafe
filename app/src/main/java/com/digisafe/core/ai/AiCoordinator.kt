package core.ai

import android.util.Log

class AiCoordinator(
    private val emotionEngine: EmotionEngine,
    private val authorityDetector: AuthorityDetector,
    private val riskEngine: RiskEngine,
    private val riskCallback: RiskCallback
) {

    fun processAudioChunk(
        filePath: String,
        duration: Long,
        phoneNumber: String,
        transcript: String?
    ) {

        val emotionScore = emotionEngine.analyzeAudio(filePath)
        val authorityScore = authorityDetector.calculateAuthorityScore(transcript ?: "")

        // 🔥 convert Long → Int here
        val riskResult = riskEngine.calculateRisk(
            duration.toInt(),
            emotionScore,
            authorityScore
        )

        Log.d("DigiSafe-AI", "AiCoordinator - Final Risk Level: ${riskResult.riskLevel}")

        val riskData = RiskData(
            phoneNumber = phoneNumber,
            duration = duration,
            emotionScore = emotionScore,
            authorityScore = authorityScore.toFloat(),
            riskLevel = riskResult.riskLevel,
            timestamp = System.currentTimeMillis()
        )

        if (riskResult.riskLevel == "HIGH") {
            riskCallback.onHighRiskDetected(riskData)
        }
    }
}