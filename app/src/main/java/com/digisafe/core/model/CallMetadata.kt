package com.digisafe.core.model

data class CallMetadata(
    val phoneNumber: String,
    val startTime: Long,
    val duration: Long,
    val audioFilePath: String?
)