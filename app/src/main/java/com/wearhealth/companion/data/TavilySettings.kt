package com.wearhealth.companion.data

import android.content.Context
import com.wearhealth.companion.shared.ApiKeyValidator

/**
 * Tavily 搜索 API Key 持久化（手表端 SharedPreferences）
 *
 * 镜像 [DeepSeekSettings] 的安全模式：保存/下发均走 [ApiKeyValidator.normalizeApiKey] 校验，
 * 校验失败不覆盖旧有效 Key，避免 BLE 尾部 NUL padding 进入 HTTP 头导致崩溃。
 * 手机端可通过 Data Layer / BLE 下发覆盖（路径 /tavily_settings）。
 */
class TavilySettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取 Tavily API Key；未配置返回空字符串 */
    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "").orEmpty().trim()

    /** 保存 Tavily API Key（含 NUL/控制字符校验，校验失败不覆盖旧 Key） */
    fun saveApiKey(key: String) {
        val normalized = ApiKeyValidator.normalizeApiKey(key).getOrNull() ?: return
        prefs.edit().putString(KEY_API_KEY, normalized).apply()
    }

    fun isConfigured(): Boolean = getApiKey().isNotBlank()

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    /** 从手机下发的 DataMap 批量更新（仅 API Key 一个字段） */
    fun applyFromRemote(apiKey: String?) {
        if (apiKey != null && apiKey.isNotBlank()) {
            ApiKeyValidator.normalizeApiKey(apiKey).getOrNull()?.let {
                prefs.edit().putString(KEY_API_KEY, it).apply()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "tavily_settings"
        private const val KEY_API_KEY = "tavily_api_key"
    }
}
