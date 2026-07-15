package com.wearhealth.companion.data

import android.content.Context
import com.wearhealth.companion.shared.ApiKeyValidator
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * API Key 管理器。
 *
 * HeartVoice API Key 只来自运行时配置并保存在手表 SharedPreferences 中。Release APK 不再
 * 内置编译时 fallback；这样全新安装必须由用户手工输入，或从已配对手机的加密 BLE
 * characteristic / Google Data Layer 获取。
 *
 * 同时提供 [refreshTrigger] 事件流，当 API Key 变化时通知 UI 刷新状态。
 */
class ApiKeyManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取运行时保存的 API Key；全新安装返回空字符串。 */
    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "").orEmpty().trim()

    /** 保存 API Key（含 NUL/控制字符校验，校验失败不覆盖旧 Key） */
    fun saveApiKey(key: String) {
        val normalized = ApiKeyValidator.normalizeApiKey(key).getOrNull() ?: return
        prefs.edit().putString(KEY_API_KEY, normalized).apply()
        notifyChanged()
    }

    /** 是否已配置 API Key（非空） */
    fun isConfigured(): Boolean = getApiKey().isNotBlank()

    /** 清除运行时保存的 API Key。 */
    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
        notifyChanged()
    }

    /** 通知 UI 刷新 API Key 状态 */
    private fun notifyChanged() {
        refreshTrigger.value = System.currentTimeMillis()
    }

    companion object {
        private const val PREFS_NAME = "api_key_prefs"
        private const val KEY_API_KEY = "heartvoice_api_key"

        /**
         * API Key 变更事件流。
         *
         * 当 [saveApiKey] / [clearApiKey] 被调用、或 WearableListenerService 收到手机下发的
         * API Key 时，会更新此 flow 的值。ViewModel 可观察此 flow 来刷新 UI 状态。
         */
        val refreshTrigger: MutableStateFlow<Long> = MutableStateFlow(0L)

        /** 触发刷新（供 WatchWearableListenerService 调用） */
        fun triggerRefresh() {
            refreshTrigger.value = System.currentTimeMillis()
        }
    }
}
