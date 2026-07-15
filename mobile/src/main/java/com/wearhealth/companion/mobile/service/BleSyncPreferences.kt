package com.wearhealth.companion.mobile.service

import android.content.Context

object BleSyncPreferences {
    private const val PREFS = "ble_sync_settings"
    private const val KEY_ENABLED = "background_sync_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
