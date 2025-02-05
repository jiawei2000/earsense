package com.example.earsense

import android.content.Context
import android.content.Context.MODE_PRIVATE

class Utils {
    companion object {
        @JvmStatic
        fun getCurrentProfile(context: Context): String {
            val prefs = context.getSharedPreferences("prefs", MODE_PRIVATE)
            val currentProfile = prefs.getString("currentProfile", profiles[0]) ?: ""
            return currentProfile
        }
    }
}