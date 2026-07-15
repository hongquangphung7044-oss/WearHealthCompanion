package com.wearhealth.companion.mobile.data

import android.content.Context
import com.wearhealth.companion.shared.ApiKeyValidator

/** Phone-side storage for the HeartVoice key exposed to the paired watch over BLE on demand. */
class MobileApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(key: String) {
        val normalized = ApiKeyValidator.normalizeApiKey(key).getOrNull() ?: return
        prefs.edit().putString(KEY_API_KEY, normalized).commit()
    }

    fun get(): String = prefs.getString(KEY_API_KEY, "").orEmpty().trim()

    /** 清除手机端保存的 API Key（供"删除缓存"功能使用）。 */
    fun clear() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    companion object {
        private const val PREFS_NAME = "mobile_api_key"
        private const val KEY_API_KEY = "heartvoice_api_key"
    }
}
