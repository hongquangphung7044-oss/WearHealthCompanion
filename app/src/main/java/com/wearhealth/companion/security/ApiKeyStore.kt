package com.wearhealth.companion.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API key is never compiled into the APK. It arrives from the paired phone over Data Layer. */
class ApiKeyStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "secure_ecg_configuration",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    fun get(): String = prefs.getString(KEY, "") ?: ""
    fun save(key: String) = prefs.edit().putString(KEY, key.trim()).apply()
    fun clear() = prefs.edit().remove(KEY).apply()
    companion object { private const val KEY = "heartvoice_api_key" }
}
