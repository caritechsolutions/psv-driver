package com.example.psvdriver

import android.content.Context

/**
 * Plain (non-secret) settings: the server base URL. Editable because the server
 * address changes over time (LAN IP -> public HTTPS host).
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("psv_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, normalize(value)).apply()
        }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val DEFAULT_BASE_URL = "https://psv.caritech.net"

        /** Trim whitespace and any trailing slashes so paths concatenate cleanly. */
        fun normalize(url: String): String = url.trim().trimEnd('/')
    }
}
