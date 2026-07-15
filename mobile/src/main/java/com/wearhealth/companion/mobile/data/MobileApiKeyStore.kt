package com.wearhealth.companion.mobile.data

import android.content.Context
import com.wearhealth.companion.shared.ApiKeyValidator

/** Phone-side storage for the HeartVoice key exposed to the paired watch over BLE on demand. */
class MobileApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(key: String) {
        // 防御性校验：仅保存归一化后合法的 Key；非法输入不覆盖已有有效 Key。
        val normalized = ApiKeyValidator.normalizeApiKey(key).getOrNull() ?: return
        prefs.edit().putString(KEY_API_KEY, normalized).commit()
    }

    fun get(): String = prefs.getString(KEY_API_KEY, "").orEmpty().trim()

    companion object {
        private const val PREFS_NAME = "mobile_api_key"
        private const val KEY_API_KEY = "heartvoice_api_key"
    }
}
