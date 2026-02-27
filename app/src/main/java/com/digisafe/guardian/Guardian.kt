package com.digisafe.guardian

/**
 * Guardian Data Model
 * Used for local encrypted storage and Firebase synchronization.
 */
data class Guardian(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val relationship: String,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)

enum class SyncStatus {
    PENDING,
    SYNCED,
    FAILED
}
