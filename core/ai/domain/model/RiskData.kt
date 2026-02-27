package core.ai.domain.model

/**
 * Represents the computed risk assessment data.
 */
data class RiskData(
    val riskScore: Float = 0.0f,
    val emotionLevel: Float = 0.0f,
    val authorityProbability: Float = 0.0f,
    val isDeceptionDetected: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
