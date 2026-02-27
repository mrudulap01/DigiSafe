package com.digisafe.core.ai

class AuthorityDetector {

    // Mock local keyword map
    private val keywordMap: Map<String, Int> = mapOf(
        "police" to 3,
        "arrest" to 4,
        "bank" to 2,
        "urgent" to 2,
        "warrant" to 5,
        "verify" to 1
    )

    fun calculateAuthorityScore(transcript: String): Int {
        var score = 0
        val lowerTranscript = transcript.lowercase()

        for ((keyword, weight) in keywordMap) {
            if (lowerTranscript.contains(keyword)) {
                score += weight
            }
        }

        return score
    }
}
