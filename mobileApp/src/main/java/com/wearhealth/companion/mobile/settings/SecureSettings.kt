package com.wearhealth.companion.mobile.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureSettings(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        "secure_settings",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun apiKey(): String = preferences.getString("api_key", "") ?: ""
    fun saveApiKey(value: String) = preferences.edit().putString("api_key", value.trim()).apply()
}
