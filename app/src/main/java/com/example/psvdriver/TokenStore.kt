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

    /** Logging out clears the token AND any open-shift state in one wipe. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    val token: String? get() = prefs.getString(KEY_TOKEN, null)
    val driver: String? get() = prefs.getString(KEY_DRIVER, null)
    val isLoggedIn: Boolean get() = token != null

    // ---- Open shift (set on sign-on; needed for pinging in the next step) ----

    /** Records the open shift plus a human-readable description and start time. */
    fun saveShift(shiftId: Int, vehicle: String, route: String, startedAt: Long, speedLimitKmh: Int) {
        prefs.edit()
            .putInt(KEY_SHIFT_ID, shiftId)
            .putString(KEY_SHIFT_VEHICLE, vehicle)
            .putString(KEY_SHIFT_ROUTE, route)
            .putLong(KEY_SHIFT_STARTED_AT, startedAt)
            .putInt(KEY_SHIFT_SPEED_LIMIT, speedLimitKmh)
            .apply()
    }

    fun clearShift() {
        prefs.edit()
            .remove(KEY_SHIFT_ID)
            .remove(KEY_SHIFT_VEHICLE)
            .remove(KEY_SHIFT_ROUTE)
            .remove(KEY_SHIFT_STARTED_AT)
            .remove(KEY_SHIFT_SPEED_LIMIT)
            .apply()
    }

    /** The open shift id, or null if not currently in service. */
    val shiftId: Int? get() = if (prefs.contains(KEY_SHIFT_ID)) prefs.getInt(KEY_SHIFT_ID, 0) else null
    val shiftVehicle: String? get() = prefs.getString(KEY_SHIFT_VEHICLE, null)
    val shiftRoute: String? get() = prefs.getString(KEY_SHIFT_ROUTE, null)
    /** Epoch millis when the shift started (for the running on-shift timer); 0 if unknown. */
    val shiftStartedAt: Long get() = prefs.getLong(KEY_SHIFT_STARTED_AT, 0L)
    /** Global speed limit (km/h) for this shift; 0 means no limit / alarm disabled. */
    val shiftSpeedLimitKmh: Int get() = prefs.getInt(KEY_SHIFT_SPEED_LIMIT, 0)

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_DRIVER = "driver"
        const val KEY_SHIFT_ID = "shift_id"
        const val KEY_SHIFT_VEHICLE = "shift_vehicle"
        const val KEY_SHIFT_ROUTE = "shift_route"
        const val KEY_SHIFT_STARTED_AT = "shift_started_at"
        const val KEY_SHIFT_SPEED_LIMIT = "shift_speed_limit"
    }
}
