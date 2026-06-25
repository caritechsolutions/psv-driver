package com.example.psvdriver

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for the auth token + driver name (EncryptedSharedPreferences).
 *
 * The server base URL is NOT secret, so it lives in plain SharedPreferences and is
 * handled separately (see [SettingsStore]).
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "psv_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(token: String, driver: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_DRIVER, driver)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    val token: String? get() = prefs.getString(KEY_TOKEN, null)
    val driver: String? get() = prefs.getString(KEY_DRIVER, null)
    val isLoggedIn: Boolean get() = token != null

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_DRIVER = "driver"
    }
}
