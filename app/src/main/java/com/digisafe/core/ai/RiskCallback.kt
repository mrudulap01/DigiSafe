package com.digisafe.core.ai

interface RiskCallback {
    fun onHighRiskDetected(riskData: RiskData)
}
