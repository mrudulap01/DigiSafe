package com.digisafe.core.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HighRiskActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this)
        textView.text = "⚠ HIGH RISK CALL DETECTED\n\nStay Calm.\nDo NOT transfer money."
        textView.textSize = 20f

        setContentView(textView)
    }
}