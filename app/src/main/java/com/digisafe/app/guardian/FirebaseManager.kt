package com.digisafe.app.guardian

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * FirebaseManager - Pushes high-risk intervention events to Firebase.
 *
 * This is best-effort: if Firebase is not configured on the device build,
 * the method safely returns false and local fallback flows continue.
 */
object FirebaseManager {

    private const val TAG = "FirebaseManager"
    private const val COLLECTION_HIGH_RISK_ALERTS = "high_risk_alerts"

    fun sendHighRiskAlert(
        context: Context,
        callerNumber: String?,
        durationSecs: Long,
        riskScore: Float,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (!ensureFirebaseInitialized(context)) {
            Log.w(TAG, "Firebase not configured. Skipping remote alert.")
            onComplete?.invoke(false)
            return
        }

        val payload = hashMapOf(
            "callerNumber" to (callerNumber ?: "Unknown"),
            "durationSecs" to durationSecs,
            "riskScore" to riskScore,
            "riskPercent" to (riskScore * 100f),
            "clientTimestamp" to System.currentTimeMillis(),
            "serverTimestamp" to FieldValue.serverTimestamp()
        )

        FirebaseFirestore.getInstance()
            .collection(COLLECTION_HIGH_RISK_ALERTS)
            .add(payload)
            .addOnSuccessListener {
                Log.d(TAG, "High-risk alert sent to Firebase.")
                onComplete?.invoke(true)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "Failed to send Firebase high-risk alert.", error)
                onComplete?.invoke(false)
            }
    }

    private fun ensureFirebaseInitialized(context: Context): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (error: Exception) {
            Log.e(TAG, "Firebase initialization failed.", error)
            false
        }
    }
}
