package com.wearhealth.companion.mobile.data

import android.content.Context
import com.wearhealth.companion.shared.ApiKeyValidator
import com.wearhealth.companion.shared.DeepSeekApiClient

/**
 * 手机端 DeepSeek 设置持久化（SharedPreferences）
 *
 * 存储：API Key、默认模型、默认思考强度、用户年龄/性别。
 * 可通过 BLE/Data Layer 下发到手表（[com.wearhealth.companion.mobile.service.DataLayerManager.sendDeepSeekSettingsToWatch]）。
 */
class MobileDeepSeekSettings(context: Context) {
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

    fun getDefaultModel(): DeepSeekApiClient.Model {
        val name = prefs.getString(KEY_DEFAULT_MODEL, DeepSeekApiClient.Model.FLASH.name) ?: ""
        return runCatching { DeepSeekApiClient.Model.valueOf(name) }.getOrDefault(DeepSeekApiClient.Model.FLASH)
    }

    fun setDefaultModel(model: DeepSeekApiClient.Model) {
        prefs.edit().putString(KEY_DEFAULT_MODEL, model.name).apply()
    }

    fun getDefaultThinking(): DeepSeekApiClient.ThinkingMode {
        val name = prefs.getString(KEY_DEFAULT_THINKING, DeepSeekApiClient.ThinkingMode.BALANCED.name) ?: ""
        return runCatching { DeepSeekApiClient.ThinkingMode.valueOf(name) }.getOrDefault(DeepSeekApiClient.ThinkingMode.BALANCED)
    }

    fun setDefaultThinking(mode: DeepSeekApiClient.ThinkingMode) {
        prefs.edit().putString(KEY_DEFAULT_THINKING, mode.name).apply()
    }

    fun getUserAge(): Int = prefs.getInt(KEY_USER_AGE, 0)
    fun setUserAge(age: Int) {
        prefs.edit().putInt(KEY_USER_AGE, age.coerceIn(0, 150)).apply()
    }

    fun getUserIsMale(): Boolean? {
        val known = prefs.getBoolean(KEY_USER_GENDER_KNOWN, false)
        return if (known) prefs.getBoolean(KEY_USER_IS_MALE, true) else null
    }

    fun setUserIsMale(isMale: Boolean?) {
        val ed = prefs.edit()
        if (isMale == null) {
            ed.putBoolean(KEY_USER_GENDER_KNOWN, false)
            ed.putBoolean(KEY_USER_IS_MALE, true)
        } else {
            ed.putBoolean(KEY_USER_GENDER_KNOWN, true)
            ed.putBoolean(KEY_USER_IS_MALE, isMale)
        }
        ed.apply()
    }

    companion object {
        private const val PREFS_NAME = "mobile_deepseek_settings"
        private const val KEY_API_KEY = "ds_api_key"
        private const val KEY_DEFAULT_MODEL = "ds_default_model"
        private const val KEY_DEFAULT_THINKING = "ds_default_thinking"
        private const val KEY_USER_AGE = "ds_user_age"
        private const val KEY_USER_IS_MALE = "ds_user_is_male"
        private const val KEY_USER_GENDER_KNOWN = "ds_user_gender_known"
    }
}
