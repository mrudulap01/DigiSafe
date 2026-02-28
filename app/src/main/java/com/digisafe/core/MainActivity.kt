package com.digisafe.core

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.digisafe.core.system.AudioRecorder
import com.digisafe.core.system.CallMonitorService

class MainActivity : AppCompatActivity() {

    private val permissionRequestCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.R.layout.simple_list_item_1)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {

        val requiredPermissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALL_LOG
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            onAllPermissionsGranted()
        } else {
            ActivityCompat.requestPermissions(
                this,
                notGranted.toTypedArray(),
                permissionRequestCode
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == permissionRequestCode) {

            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                onAllPermissionsGranted()
            } else {
                Log.d("DigiSafe", "Permissions not granted")
            }
        }
    }

    private fun onAllPermissionsGranted() {

        Log.d("DigiSafe", "All permissions granted")

        // Start Foreground Service
        val intent = Intent(this, CallMonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}


