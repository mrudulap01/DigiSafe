package com.digisafe.guardian

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.digisafe.guardian.backend.FirebaseManager
import com.digisafe.guardian.dashboard.GuardianDashboardActivity
import com.digisafe.guardian.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // PHASE 2: Topic Subscription
        FirebaseMessaging.getInstance().subscribeToTopic("test")
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("FCM_TOPIC", "Subscribed to topic: test")
                    Toast.makeText(this, "Subscribed to test topic", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("FCM_TOPIC", "Topic subscription failed", task.exception)
                }
            }

        requestNotificationPermission()
        
        // PHASE 2: Token Retrieval & Sync
        updateFcmToken()
        
        // TEST MODE: Visual debug call
        runTestMode()

        // AUTO-NAVIGATION: Redirect to Dashboard for monitoring
        startActivity(Intent(this, GuardianDashboardActivity::class.java))
        finish()
    }

    private fun runTestMode() {
        val user = FirebaseAuth.getInstance().currentUser
        Log.d(TAG, "========== TEST MODE ACTIVE ==========")
        Log.d(TAG, "User ID: ${user?.uid ?: "Not Authenticated"}")
        FirebaseDatabase.getInstance().getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                Log.d(TAG, "Firebase Connected: $connected")
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                    if (isGranted) Log.d(TAG, "Notification permission granted")
                    else Log.w(TAG, "Notification permission denied")
                } .launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun updateFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.e(TAG, "FCM Token Fetch Failed", task.exception)
                return@addOnCompleteListener
            }

            val token = task.result
            Log.d("FCM_TOKEN", "Token: $token")
            
            // Log to RTDB for easy dashboard access
            FirebaseDatabase.getInstance().reference.child("debug_token").setValue(token)
            
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser != null) {
                lifecycleScope.launch {
                    FirebaseManager.updateFcmToken(currentUser.uid, token)
                }
            }
        }
    }
}
