package com.digisafe.guardian.dashboard

/**
 * DashboardEvent: Sealed class representing the different types of items on the timeline.
 * Ensures type safety and exhaustive handling in the RecyclerView adapter.
 */
sealed class DashboardEvent(
    val id: String,
    val timestamp: Long,
    val type: EventType
) {
    data class Alert(
        val alertId: String,
        val riskScore: Double,
        val riskLevel: String, // LOW, MEDIUM, HIGH
        val callerNumber: String,
        val time: Long
    ) : DashboardEvent(alertId, time, EventType.ALERT)

    data class Transaction(
        val txId: String,
        val amount: Double,
        val status: String,
        val merchant: String,
        val time: Long
    ) : DashboardEvent(txId, time, EventType.TRANSACTION)

    data class Approval(
        val approvalId: String,
        val state: String, // PENDING, APPROVED, DENIED, TIMEOUT
        val time: Long
    ) : DashboardEvent(approvalId, time, EventType.APPROVAL)

    data class Evidence(
        val evidenceId: String,
        val metadata: String,
        val time: Long
    ) : DashboardEvent(evidenceId, time, EventType.EVIDENCE)
}

enum class EventType {
    ALERT, TRANSACTION, APPROVAL, EVIDENCE
}
