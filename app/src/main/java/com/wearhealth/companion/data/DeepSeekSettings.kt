package com.wearhealth.companion.data

import android.content.Context
import com.wearhealth.companion.network.DeepSeekApiClient
import com.wearhealth.companion.shared.ApiKeyValidator

/**
 * DeepSeek 设置持久化（手表端 SharedPreferences）
 *
 * 存储：API Key、默认模型、默认思考强度、用户年龄/性别。
 * 手机端可通过 Data Layer / BLE 下发覆盖这些设置。
 */
class DeepSeekSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取运行时保存的 DeepSeek API Key；未配置返回空字符串 */
    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "").orEmpty().trim()

    /** 保存 DeepSeek API Key（含 NUL/控制字符校验，校验失败不覆盖旧 Key） */
    fun saveApiKey(key: String) {
        val normalized = ApiKeyValidator.normalizeApiKey(key).getOrNull() ?: return
        prefs.edit().putString(KEY_API_KEY, normalized).apply()
    }

    fun isConfigured(): Boolean = getApiKey().isNotBlank()

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    /** 默认模型：FLASH / PRO */
    fun getDefaultModel(): DeepSeekApiClient.Model {
        val name = prefs.getString(KEY_DEFAULT_MODEL, DeepSeekApiClient.Model.FLASH.name) ?: ""
        return runCatching { DeepSeekApiClient.Model.valueOf(name) }.getOrDefault(DeepSeekApiClient.Model.FLASH)
    }

    fun setDefaultModel(model: DeepSeekApiClient.Model) {
        prefs.edit().putString(KEY_DEFAULT_MODEL, model.name).apply()
    }

    /** 默认思考强度：FAST / BALANCED / MAX */
    fun getDefaultThinking(): DeepSeekApiClient.ThinkingMode {
        val name = prefs.getString(KEY_DEFAULT_THINKING, DeepSeekApiClient.ThinkingMode.BALANCED.name) ?: ""
        return runCatching { DeepSeekApiClient.ThinkingMode.valueOf(name) }.getOrDefault(DeepSeekApiClient.ThinkingMode.BALANCED)
    }

    fun setDefaultThinking(mode: DeepSeekApiClient.ThinkingMode) {
        prefs.edit().putString(KEY_DEFAULT_THINKING, mode.name).apply()
    }

    /** 用户年龄（0=未知） */
    fun getUserAge(): Int = prefs.getInt(KEY_USER_AGE, 0)
    fun setUserAge(age: Int) {
        prefs.edit().putInt(KEY_USER_AGE, age.coerceIn(0, 150)).apply()
    }

    /**
     * 用户性别：null=未知，true=男，false=女
     * 用两个 key 组合表示三态（SharedPreferences 没有可空 Boolean）
     */
    fun getUserIsMale(): Boolean? {
        val known = prefs.getBoolean(KEY_USER_GENDER_KNOWN, false)
        return if (known) prefs.getBoolean(KEY_USER_IS_MALE, true) else null
    }

    fun setUserIsMale(isMale: Boolean?) {
        val ed = prefs.edit()
        if (isMale == null) {
            ed.putBoolean(KEY_USER_GENDER_KNOWN, false)
            ed.putBoolean(KEY_USER_IS_MALE, true) // 占位
        } else {
            ed.putBoolean(KEY_USER_GENDER_KNOWN, true)
            ed.putBoolean(KEY_USER_IS_MALE, isMale)
        }
        ed.apply()
    }

    /**
     * 从手机下发的 DataMap 批量更新所有设置
     * 接收方：WatchWearableListenerService（第 3 批接入）
     */
    fun applyFromRemote(
        apiKey: String?,
        defaultModel: String?,
        defaultThinking: String?,
        userAge: Int,
        userIsMale: Boolean?,
    ) {
        val ed = prefs.edit()
        if (apiKey != null && apiKey.isNotBlank()) {
            ApiKeyValidator.normalizeApiKey(apiKey).getOrNull()?.let {
                ed.putString(KEY_API_KEY, it)
            }
        }
        if (defaultModel != null) {
            runCatching { DeepSeekApiClient.Model.valueOf(defaultModel) }
                .onSuccess { ed.putString(KEY_DEFAULT_MODEL, it.name) }
        }
        if (defaultThinking != null) {
            runCatching { DeepSeekApiClient.ThinkingMode.valueOf(defaultThinking) }
                .onSuccess { ed.putString(KEY_DEFAULT_THINKING, it.name) }
        }
        if (userAge > 0) ed.putInt(KEY_USER_AGE, userAge.coerceIn(0, 150))
        if (userIsMale != null) {
            ed.putBoolean(KEY_USER_GENDER_KNOWN, true)
            ed.putBoolean(KEY_USER_IS_MALE, userIsMale)
        } else if (userAge == 0) {
            // 明确清空性别（手机端也清空时）
            ed.putBoolean(KEY_USER_GENDER_KNOWN, false)
        }
        ed.apply()
    }

    companion object {
        private const val PREFS_NAME = "deepseek_settings"
        private const val KEY_API_KEY = "ds_api_key"
        private const val KEY_DEFAULT_MODEL = "ds_default_model"
        private const val KEY_DEFAULT_THINKING = "ds_default_thinking"
        private const val KEY_USER_AGE = "ds_user_age"
        private const val KEY_USER_IS_MALE = "ds_user_is_male"
        private const val KEY_USER_GENDER_KNOWN = "ds_user_gender_known"
    }
}
