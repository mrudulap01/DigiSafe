package com.digisafe.app.guardian

import android.content.Context

/**
 * GuardianManager - Stores and retrieves guardian contact information
 * using SharedPreferences.
 */
object GuardianManager {

    private const val PREFS_NAME = "digisafe_guardian"
    private const val KEY_GUARDIAN_NAME = "guardian_name"
    private const val KEY_GUARDIAN_PHONE = "guardian_phone"

    fun saveGuardian(context: Context, name: String, phone: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GUARDIAN_NAME, name)
            .putString(KEY_GUARDIAN_PHONE, phone)
            .apply()
    }

    fun getGuardianName(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GUARDIAN_NAME, null)
    }

    fun getGuardianPhone(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GUARDIAN_PHONE, null)
    }

    fun isGuardianSet(context: Context): Boolean {
        val phone = getGuardianPhone(context)
        return !phone.isNullOrBlank()
    }
}
