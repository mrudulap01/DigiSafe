package com.digisafe

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.digisafe.core.system.CallMonitorService

class MainActivity : AppCompatActivity() {

    private val permissionRequestCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNecessaryPermissions()
        requestDefaultDialerRole()
    }

    private fun requestNecessaryPermissions() {

        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val permissionsToRequest = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                permissionRequestCode
            )
        } else {
            startMonitoringService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == permissionRequestCode) {

            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
                startMonitoringService()
            } else {
                Toast.makeText(this, "Permissions required for DigiSafe to work", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startMonitoringService() {
        val serviceIntent = Intent(this, CallMonitorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun requestDefaultDialerRole() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                val intent =
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                startActivity(intent)
            }
        }
    }
}