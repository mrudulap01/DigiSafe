package core.ai

data class RiskData(
    val phoneNumber: String,
    val duration: Long,
    val emotionScore: Float,
    val authorityScore: Int,
    val riskLevel: String,
    val timestamp: Long
)