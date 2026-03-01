package com.digisafe.app.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * HighRiskManager - Central state manager for risk levels.
 *
 * Coordinates between services: triggers overlay, guardian notification,
 * and banking shield on state transitions.
 */
object HighRiskManager {

    private const val TAG = "HighRiskManager"
    private const val PREFS_NAME = "digisafe_state"
    private const val KEY_CALLS_MONITORED = "calls_monitored"
    private const val KEY_THREATS_BLOCKED = "threats_blocked"

    // Current risk state
    private val _riskLevel = MutableLiveData(RiskEngine.RiskLevel.SAFE)
    val riskLevel: LiveData<RiskEngine.RiskLevel> = _riskLevel

    private val _riskScore = MutableLiveData(0f)
    val riskScore: LiveData<Float> = _riskScore

    private val _isCallActive = MutableLiveData(false)
    val isCallActive: LiveData<Boolean> = _isCallActive

    private val _callerNumber = MutableLiveData<String?>(null)
    val callerNumber: LiveData<String?> = _callerNumber

    private val _callStartTime = MutableLiveData(0L)
    val callStartTime: LiveData<Long> = _callStartTime

    // Banking app detection
    private val _isBankingAppOpen = MutableLiveData(false)
    val isBankingAppOpen: LiveData<Boolean> = _isBankingAppOpen

    private val riskEngine = RiskEngine()

    // Dynamic factors
    private var currentKeywordProbability = 0f
    private var currentIsUnknownCaller = false
    private var suspiciousPhraseHits = 0
    private var transactionAttemptDetected = false

    // Listeners for state changes
    private val stateChangeListeners = mutableListOf<OnRiskStateChangeListener>()

    interface OnRiskStateChangeListener {
        fun onRiskStateChanged(
            newLevel: RiskEngine.RiskLevel,
            score: Float,
            callerNumber: String?,
            durationSecs: Long
        )
    }

    fun addStateChangeListener(listener: OnRiskStateChangeListener) {
        stateChangeListeners.add(listener)
    }

    fun removeStateChangeListener(listener: OnRiskStateChangeListener) {
        stateChangeListeners.remove(listener)
    }

    /**
     * Called when an incoming call is detected.
     */
    fun onCallStarted(phoneNumber: String?, isUnknown: Boolean) {
        Log.d(TAG, "Call started: $phoneNumber, unknown=$isUnknown")
        currentIsUnknownCaller = isUnknown
        suspiciousPhraseHits = 0
        transactionAttemptDetected = false
        currentKeywordProbability = 0f
        _isCallActive.postValue(true)
        _callerNumber.postValue(phoneNumber)
        _callStartTime.postValue(System.currentTimeMillis())
        recalculateRisk()
    }

    /**
     * Called when the call ends.
     */
    fun onCallEnded(context: Context) {
        Log.d(TAG, "Call ended")
        _isCallActive.postValue(false)
        resetRisk()

        val prefs = getPrefs(context)
        val count = prefs.getInt(KEY_CALLS_MONITORED, 0)
        prefs.edit().putInt(KEY_CALLS_MONITORED, count + 1).apply()
    }

    /**
     * Called when banking app is detected as opened.
     */
    fun onBankingAppDetected(isOpen: Boolean) {
        Log.d(TAG, "Banking app open: $isOpen")
        _isBankingAppOpen.postValue(isOpen)
        if (isOpen && _isCallActive.value == true) {
            recalculateRisk()
        }
    }

    /**
     * Update keyword detection probability from AI layer.
     */
    fun updateKeywordProbability(probability: Float) {
        currentKeywordProbability = probability.coerceIn(0f, 1f)
        if (_isCallActive.value == true) {
            recalculateRisk()
        }
    }

    /**
     * Escalate keyword probability when suspicious phrases continue.
     */
    fun onSuspiciousPhraseDetected() {
        suspiciousPhraseHits += 1
        val phraseScore = (suspiciousPhraseHits * 0.2f).coerceIn(0f, 1f)
        if (phraseScore > currentKeywordProbability) {
            currentKeywordProbability = phraseScore
        }
        if (_isCallActive.value == true) {
            recalculateRisk()
        }
    }

    fun getSuspiciousPhraseHits(): Int = suspiciousPhraseHits

    fun onTransactionAttemptDetected() {
        transactionAttemptDetected = true
    }

    fun hasTransactionAttemptDetected(): Boolean = transactionAttemptDetected

    /**
     * Recalculate risk score based on current factors.
     */
    fun recalculateRisk() {
        val durationSecs = if ((_callStartTime.value ?: 0L) > 0L) {
            (System.currentTimeMillis() - (_callStartTime.value ?: 0L)) / 1000
        } else {
            0L
        }

        val factors = RiskEngine.RiskFactors(
            keywordProbability = currentKeywordProbability,
            isUnknownCaller = currentIsUnknownCaller,
            callDurationSeconds = durationSecs,
            isBankingAppOpen = _isBankingAppOpen.value ?: false
        )

        val result = riskEngine.calculateRisk(factors)
        val previousLevel = _riskLevel.value

        _riskScore.postValue(result.score)
        _riskLevel.postValue(result.level)

        Log.d(TAG, "Risk recalculated: score=${result.score}, level=${result.level}")

        if (result.level != previousLevel) {
            notifyListeners(result.level, result.score, _callerNumber.value, durationSecs)
        }
    }

    /**
     * Reset all risk state to SAFE.
     */
    fun resetRisk() {
        _riskLevel.postValue(RiskEngine.RiskLevel.SAFE)
        _riskScore.postValue(0f)
        _isBankingAppOpen.postValue(false)
        currentKeywordProbability = 0f
        currentIsUnknownCaller = false
        suspiciousPhraseHits = 0
        transactionAttemptDetected = false
        notifyListeners(RiskEngine.RiskLevel.SAFE, 0f, null, 0)
    }

    fun getCallDurationSeconds(): Long {
        val startTime = _callStartTime.value ?: return 0
        return if (startTime > 0) (System.currentTimeMillis() - startTime) / 1000 else 0
    }

    fun getCallsMonitored(context: Context): Int {
        return getPrefs(context).getInt(KEY_CALLS_MONITORED, 0)
    }

    fun getThreatsBlocked(context: Context): Int {
        return getPrefs(context).getInt(KEY_THREATS_BLOCKED, 0)
    }

    fun incrementThreatsBlocked(context: Context) {
        val prefs = getPrefs(context)
        val count = prefs.getInt(KEY_THREATS_BLOCKED, 0)
        prefs.edit().putInt(KEY_THREATS_BLOCKED, count + 1).apply()
    }

    private fun notifyListeners(
        level: RiskEngine.RiskLevel,
        score: Float,
        callerNumber: String?,
        durationSecs: Long
    ) {
        stateChangeListeners.forEach { listener ->
            listener.onRiskStateChanged(level, score, callerNumber, durationSecs)
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
