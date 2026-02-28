package com.digisafe

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import core.ai.EmotionEngine   // ✅ corrected import
import core.ai.AiEngineTest

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val aiTest = AiEngineTest() //for testing ai
        aiTest.runTest(this)

        val emotionEngine = EmotionEngine(this)

        val dummyFeatures = FloatArray(40) { 0.5f }

        val score = emotionEngine.analyzeAudio(dummyFeatures)

        Log.d("DigiSafe-Test", "Emotion score from test: $score")
    }
}