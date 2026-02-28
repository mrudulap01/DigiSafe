package com.digisafe.core.system

import android.content.Context
import android.util.Log

class CallTerminator(private val context: Context) {

    fun endCall() {
        Log.d("CallTerminator", "End Call Triggered")
    }
}