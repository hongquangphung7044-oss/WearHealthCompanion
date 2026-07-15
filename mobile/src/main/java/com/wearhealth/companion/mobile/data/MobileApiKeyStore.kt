package com.wearhealth.companion.mobile.data

import android.content.Context

/** Phone-side storage for the HeartVoice key exposed to the paired watch over BLE on demand. */
class MobileApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).commit()
    }

    fun get(): String = prefs.getString(KEY_API_KEY, "").orEmpty().trim()

    companion object {
        private const val PREFS_NAME = "mobile_api_key"
        private const val KEY_API_KEY = "heartvoice_api_key"
    }
}
