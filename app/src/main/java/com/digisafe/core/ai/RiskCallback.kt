package core.ai

interface RiskCallback {
    fun onHighRiskDetected(riskData: RiskData)
}