package com.digisafe.core.ai

class CognitiveShieldManager(private val riskCallback: RiskCallback) {

    fun processRiskData(riskData: RiskData) {
        if (riskData.riskLevel == "HIGH") {
            riskCallback.onHighRiskDetected(riskData)
        }
    }
}
