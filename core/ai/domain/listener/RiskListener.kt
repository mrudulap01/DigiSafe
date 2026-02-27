package core.ai.domain.listener

import core.ai.domain.model.RiskData

/**
 * Callback interface for receiving risk assessment updates.
 */
interface RiskListener {
    fun onRiskEvaluated(riskData: RiskData)
    fun onError(throwable: Throwable)
}
