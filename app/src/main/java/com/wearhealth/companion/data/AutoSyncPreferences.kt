package com.wearhealth.companion.data

import android.content.Context

class AutoSyncPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("watch_sync_settings", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean("auto_sync_after_measurement", true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_sync_after_measurement", enabled).apply()
    }
}
