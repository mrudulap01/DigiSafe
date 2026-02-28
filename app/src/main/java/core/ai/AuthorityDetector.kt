package core.ai

import android.util.Log

class AuthorityDetector {

    private val keywordMap = mapOf(
        "police" to 20,
        "arrest" to 25,
        "legal action" to 30,
        "transfer now" to 35,
        "confidential" to 15,
        "do not disconnect" to 40
    )

    fun calculateAuthorityScore(transcript: String?): Int {
        if (transcript == null) return 0

        var score = 0
        val lowerText = transcript.lowercase()

        for ((keyword, weight) in keywordMap) {
            if (lowerText.contains(keyword)) {
                score += weight
            }
        }

        Log.d("DigiSafe-AI", "Authority Score: $score")
        return score
    }
}