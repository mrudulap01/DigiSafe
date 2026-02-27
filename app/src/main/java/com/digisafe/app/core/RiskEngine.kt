package com.digisafe.app.core

/**
 * Risk Engine - Behavioral risk scoring for incoming calls.
 *
 * Risk Score = (KeywordProbability × 0.4) + (UnknownCaller × 0.2) +
 *              (CallDurationScore × 0.2) + (BankingAppOverlap × 0.2)
 *
 * Thresholds:
 *   > 0.4 → WARNING
 *   > 0.7 → HIGH RISK
 */
class RiskEngine {

    data class RiskFactors(
        val keywordProbability: Float = 0f,    // 0.0 - 1.0
        val isUnknownCaller: Boolean = false,
        val callDurationSeconds: Long = 0,
        val isBankingAppOpen: Boolean = false
    )

    data class RiskResult(
        val score: Float,
        val level: RiskLevel,
        val factors: RiskFactors
    )

    enum class RiskLevel {
        SAFE,
        WARNING,
        HIGH_RISK
    }

    companion object {
        // Weights
        private const val KEYWORD_WEIGHT = 0.4f
        private const val UNKNOWN_CALLER_WEIGHT = 0.2f
        private const val DURATION_WEIGHT = 0.2f
        private const val BANKING_OVERLAP_WEIGHT = 0.2f

        // Thresholds
        const val WARNING_THRESHOLD = 0.4f
        const val HIGH_RISK_THRESHOLD = 0.7f

        // Duration thresholds (seconds)
        private const val DURATION_WARNING_SECS = 120L   // 2 minutes
        private const val DURATION_HIGH_SECS = 300L       // 5 minutes
    }

    /**
     * Calculate risk score from all behavioral factors.
     */
    fun calculateRisk(factors: RiskFactors): RiskResult {
        val keywordScore = factors.keywordProbability * KEYWORD_WEIGHT

        val unknownCallerScore = if (factors.isUnknownCaller) {
            UNKNOWN_CALLER_WEIGHT
        } else {
            0f
        }

        val durationScore = calculateDurationScore(factors.callDurationSeconds) * DURATION_WEIGHT

        val bankingScore = if (factors.isBankingAppOpen) {
            BANKING_OVERLAP_WEIGHT
        } else {
            0f
        }

        val totalScore = (keywordScore + unknownCallerScore + durationScore + bankingScore)
            .coerceIn(0f, 1f)

        val level = when {
            totalScore >= HIGH_RISK_THRESHOLD -> RiskLevel.HIGH_RISK
            totalScore >= WARNING_THRESHOLD -> RiskLevel.WARNING
            else -> RiskLevel.SAFE
        }

        return RiskResult(
            score = totalScore,
            level = level,
            factors = factors
        )
    }

    /**
     * Calculate duration-based risk score (0.0 - 1.0).
     * Linear ramp from 0 at 0 seconds to 1.0 at DURATION_HIGH_SECS.
     */
    private fun calculateDurationScore(durationSecs: Long): Float {
        return when {
            durationSecs <= 0 -> 0f
            durationSecs >= DURATION_HIGH_SECS -> 1f
            durationSecs >= DURATION_WARNING_SECS -> {
                val range = DURATION_HIGH_SECS - DURATION_WARNING_SECS
                val progress = durationSecs - DURATION_WARNING_SECS
                0.5f + (progress.toFloat() / range.toFloat()) * 0.5f
            }
            else -> {
                (durationSecs.toFloat() / DURATION_WARNING_SECS.toFloat()) * 0.5f
            }
        }
    }
}
