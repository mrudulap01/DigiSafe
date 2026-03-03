package com.digisafe.guardian.security

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket

/**
 * DeviceIntegrityManager: Production-grade runtime security attestation.
 * 
 * DESIGN PRINCIPLES:
 * 1. Multi-signal Heuristics: Combines root, emulator, and debugger signals to minimize false positives.
 * 2. Fail-Closed: High-severity signals trigger an immediate compromised state.
 * 3. Sanitized Reporting: No specific detection details are logged to avoid informing attackers.
 */
object DeviceIntegrityManager {

    private const val TAG = "DeviceIntegrityManager"
    private const val FRIDA_PORT = 27042
    private const val RISK_THRESHOLD = 50

    /**
     * Unified evaluation of device security posture.
     */
    fun isDeviceCompromised(context: Context): Boolean {
        val rootScore = evaluateRootRisk()
        val emulatorScore = evaluateEmulatorRisk()
        val runtimeScore = evaluateRuntimeRisk()

        val totalRisk = rootScore + emulatorScore + runtimeScore

        if (totalRisk >= RISK_THRESHOLD) {
            Log.w(TAG, "Integrity check failed")
            return true
        }
        return false
    }

    /**
     * 1. ROOT DETECTION (Score: 100 on strong match)
     */
    private fun evaluateRootRisk(): Int {
        var score = 0
        
        // Signal A: Known Su Binaries
        val paths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk")
        if (paths.any { File(it).exists() }) score += 100

        // Signal B: Test Keys in Build Tags
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) score += 60

        // Signal C: Writable System Partition Heuristic
        try {
            val process = Runtime.getRuntime().exec("mount")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            reader.useLines { lines ->
                if (lines.any { it.contains(" /system ") && it.contains(" rw,") }) score += 100
            }
        } catch (e: Exception) { /* Silent fail */ }

        // Signal D: Runtime "which su" check
        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            if (BufferedReader(InputStreamReader(process.inputStream)).readLine() != null) score += 100
        } catch (e: Exception) { /* Silent fail */ }

        return score
    }

    /**
     * 2. EMULATOR DETECTION (Cumulative Scoring)
     */
    private fun evaluateEmulatorRisk(): Int {
        var score = 0

        val fingerprint = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val hardware = Build.HARDWARE ?: ""
        val product = Build.PRODUCT ?: ""

        if (fingerprint.contains("generic")) score += 30
        if (model.contains("Emulator") || model.contains("Android SDK built for x86")) score += 30
        if (manufacturer.contains("Genymotion")) score += 50
        if (hardware.contains("goldfish") || hardware.contains("ranchu")) score += 50
        if (product.contains("sdk") || product.contains("google_sdk")) score += 30

        return score
    }

    /**
     * 3. RUNTIME TAMPER DETECTION (Score: 100 on active debug/instrumentation)
     */
    private fun evaluateRuntimeRisk(): Int {
        // Signal A: Debugger attached
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return 100

        // Signal B: Frida Heuristic (Proc Map Scan)
        if (checkFridaHeuristics()) return 100

        return 0
    }

    /**
     * Scans for Frida instrumentation evidence.
     */
    private fun checkFridaHeuristics(): Boolean {
        return try {
            // Check maps for Frida artifacts
            val maps = File("/proc/self/maps")
            if (maps.exists()) {
                val found = maps.bufferedReader().useLines { lines ->
                    lines.any { it.contains("frida-agent", ignoreCase = true) }
                }
                if (found) return true
            }

            // Check common Frida port
            var socket: Socket? = null
            try {
                socket = Socket("127.0.0.1", FRIDA_PORT)
                true // Port is open
            } catch (e: Exception) {
                false
            } finally {
                socket?.close()
            }
        } catch (e: Exception) {
            false
        }
    }
}
