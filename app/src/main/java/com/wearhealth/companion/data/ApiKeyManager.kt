package com.wearhealth.companion.data

import android.content.Context
import com.wearhealth.companion.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * API Key 管理器
 *
 * 使用 SharedPreferences 存储 HeartVoice API Key（不再用 BuildConfig 硬编码）。
 * 支持手机端通过 Wearable Data Layer 远程下发 API Key。
 *
 * 优先级：SharedPreferences 中存储的值 > BuildConfig.HEARTVOICE_API_KEY（编译时注入的默认值）
 *
 * 同时提供 [refreshTrigger] 事件流，当 API Key 变化时通知 UI 刷新状态。
 */
class ApiKeyManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取存储的 API Key
     *
     * 如果 SharedPreferences 中没有，回退到 BuildConfig.HEARTVOICE_API_KEY。
     */
    fun getApiKey(): String {
        val stored = prefs.getString(KEY_API_KEY, null)
        return if (stored.isNullOrEmpty()) {
            BuildConfig.HEARTVOICE_API_KEY
        } else {
            stored
        }
    }

    /** 保存 API Key */
    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
        notifyChanged()
    }

    /** 是否已配置 API Key（非空） */
    fun isConfigured(): Boolean = getApiKey().isNotBlank()

    /** 清除存储的 API Key（回退到 BuildConfig 默认值） */
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
