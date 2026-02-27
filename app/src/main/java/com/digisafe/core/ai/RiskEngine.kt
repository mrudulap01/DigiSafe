package com.digisafe.core.ai

import android.content.Context
import android.util.Log

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

        // Normalize duration (assuming duration is in seconds)
        val durationMinutes = duration / 60
        val normalizedDurationScore = when {
            durationMinutes > 15 -> 100f
            durationMinutes in 10..15 -> 70f
            durationMinutes in 5..9 -> 40f
            else -> 10f
        }

        // Weighted Scoring
        val durationWeight = 0.2f
        val emotionWeight = 0.4f
        val authorityWeight = 0.4f

        // Calculate final score
        // (emotionScore is typically 0.0-1.0 from TF-Lite, multiplying by 100 to map to 0-100 scale)
        val finalScore = (normalizedDurationScore * durationWeight) +
                (emotionScore * 100f * emotionWeight) +
                (authorityScore * authorityWeight) // Assuming authorityScore is already on a 0-100 scale or similar

        val riskLevel = when {
            finalScore >= 71f -> "HIGH"
            finalScore >= 41f -> "MEDIUM"
            else -> "LOW"
        }

        Log.d("DigiSafe-AI", "RiskEngine - Final Risk Level: $riskLevel (Score: $finalScore, Emotion: $emotionScore, Authority: $authorityScore)")

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
