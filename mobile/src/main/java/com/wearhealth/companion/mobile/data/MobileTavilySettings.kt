package com.wearhealth.companion.mobile.data

import android.content.Context
import com.wearhealth.companion.shared.ApiKeyValidator

/**
 * 手机端 Tavily 搜索 API Key 持久化（SharedPreferences）
 *
 * 镜像 [MobileDeepSeekSettings] 的安全模式：保存/下发均走 [ApiKeyValidator.normalizeApiKey] 校验。
 * 可通过 BLE/Data Layer 下发到手表（[com.wearhealth.companion.mobile.service.DataLayerManager.sendTavilySettingsToWatch]）。
 */
class MobileTavilySettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "").orEmpty().trim()

    fun saveApiKey(key: String) {
        val normalized = ApiKeyValidator.normalizeApiKey(key).getOrNull() ?: return
        prefs.edit().putString(KEY_API_KEY, normalized).commit()
    }

    fun isConfigured(): Boolean = getApiKey().isNotBlank()

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    companion object {
        private const val PREFS_NAME = "mobile_tavily_settings"
        private const val KEY_API_KEY = "tavily_api_key"
    }
}
