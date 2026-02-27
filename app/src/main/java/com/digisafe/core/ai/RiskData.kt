package com.digisafe.core.ai

data class RiskData(
    val phoneNumber: String,
    val duration: Int,
    val emotionScore: Float,
    val authorityScore: Float,
    val riskLevel: String,
    val timestamp: Long
)
