package com.digisafe.core.system

interface AudioChunkListener {

    fun onAudioChunkReady(
        filePath: String,
        phoneNumber: String?,
        duration: Long
    )
}