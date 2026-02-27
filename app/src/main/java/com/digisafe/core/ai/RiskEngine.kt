package com.digisafe.core.ai

import android.content.Context

class RiskEngine(context: Context) {

    private val emotionEngine = EmotionEngine(context)
    private val authorityDetector = AuthorityDetector()

    fun analyze(
        phoneNumber: String,
        audioFilePath: String,
        transcript: String,
        duration: Int
    ): RiskData {
        val emotionScore = emotionEngine.analyzeAudio(audioFilePath)
        val authorityScore = authorityDetector.calculateAuthorityScore(transcript).toFloat()

        // Simple mock threshold logic combining the three factors
        val normalizedDurationScore = if (duration > 300) 10f else (duration / 30f)
        val totalScore = (emotionScore * 10f) + authorityScore + normalizedDurationScore

        val riskLevel = when {
            totalScore >= 15f -> "HIGH"
            totalScore >= 7f -> "MEDIUM"
            else -> "LOW"
        }

        return RiskData(
            phoneNumber = phoneNumber,
            duration = duration,
            emotionScore = emotionScore,
            authorityScore = authorityScore,
            riskLevel = riskLevel,
            timestamp = System.currentTimeMillis()
        )
    }
}
