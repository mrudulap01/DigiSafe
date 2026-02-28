// FINAL RISK CONTRACT:
// LOW = Safe
// MEDIUM = Suspicious (monitor)
// HIGH = Scam likely (trigger protection)
package core.ai

import android.util.Log

class RiskEngine {

    fun calculateRisk(
        duration: Long,
        emotionScore: Float,
        authorityScore: Int
    ): String {

        val durationScore = when {
            duration > 900 -> 100     // 15 min
            duration > 600 -> 70      // 10 min
            duration > 300 -> 40      // 5 min
            else -> 10
        }

        val riskScore =
            (0.2 * durationScore) +
                    (0.4 * (emotionScore * 100)) +
                    (0.4 * authorityScore)

        val level = when {
            riskScore > 65 -> "HIGH"
            riskScore > 40 -> "MEDIUM"
            else -> "LOW"
        }

        Log.d("DigiSafe-AI", "Final Risk Score: $riskScore Level: $level")

        return level
    }
}
