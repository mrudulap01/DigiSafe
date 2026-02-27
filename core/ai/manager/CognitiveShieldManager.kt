package core.ai.manager

import core.ai.data.engine.RiskEngine
import core.ai.domain.listener.RiskListener
import core.ai.domain.model.RiskData

/**
 * High-level manager that orchestrates the overall cognitive shield AI pipeline.
 * Follows facade pattern to provide a simple clean API for the rest of the application.
 */
class CognitiveShieldManager(
    private val riskEngine: RiskEngine
) {
    private var riskListener: RiskListener? = null
    private var isRunning = false

    /**
     * Sets the listener to receive risk assessment updates.
     */
    fun setRiskListener(listener: RiskListener) {
        this.riskListener = listener
    }

    /**
     * Starts the cognitive shield monitoring.
     */
    fun startMonitoring() {
        if (isRunning) return
        isRunning = true
        // TODO: Start audio/data capture and feed it into the risk engine
    }

    /**
     * Stops monitoring and releases resources.
     */
    fun stopMonitoring() {
        isRunning = false
        // TODO: Stop capture mechanisms
    }

    /**
     * Feeds data manually into the shield for evaluation.
     */
    fun processData(audioData: ByteArray, textTranscript: String? = null) {
        if (!isRunning) return
        
        try {
            val riskData = riskEngine.calculateRisk(audioData, textTranscript)
            riskListener?.onRiskEvaluated(riskData)
        } catch (e: Exception) {
            riskListener?.onError(e)
        }
    }
    
    fun destroy() {
        stopMonitoring()
        riskEngine.release()
        riskListener = null
    }
}
