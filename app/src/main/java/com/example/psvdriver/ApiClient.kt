package com.example.psvdriver

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.TimeUnit

// ---- Models ------------------------------------------------------------------

/** A vehicle the driver is allowed to sign on to. */
data class Vehicle(val id: Int, val registration: String, val label: String, val capacity: Int) {
    // Shown directly in the Spinner via ArrayAdapter.
    override fun toString(): String =
        if (label.isBlank()) registration else "$registration — $label"
}

/** A route the driver may run. */
data class Route(val id: Int, val routeNumber: String, val name: String) {
    override fun toString(): String =
        if (name.isBlank()) routeNumber else "$routeNumber — $name"
}

// ---- Result types ------------------------------------------------------------

/** Outcome of a login attempt. */
sealed class LoginResult {
    data class Success(val token: String, val driver: String) : LoginResult()
    /** Server returned 401 invalid_credentials (generic; no enumeration). */
    object InvalidCredentials : LoginResult()
    /** Could not reach the server, bad URL, timeout, or an unexpected response. */
    data class NetworkError(val message: String) : LoginResult()
}

/** Outcome of fetching the driver's allowed vehicles + routes. */
sealed class VehiclesResult {
    data class Success(
        val vehicles: List<Vehicle>,
        val routes: List<Route>,
        /** Global speed limit in km/h; 0 (or missing) means no limit / alarm disabled. */
        val speedLimitKmh: Int,
    ) : VehiclesResult()
    /** 401 — token missing/expired; caller should send the user back to login. */
    object Unauthorized : VehiclesResult()
    data class NetworkError(val message: String) : VehiclesResult()
}

/** Outcome of a sign-on attempt. */
sealed class SignOnResult {
    data class Success(val shiftId: Int, val driver: String) : SignOnResult()
    /** 401 — token missing/expired; caller should send the user back to login. */
    object Unauthorized : SignOnResult()
    /** 422/409 — request understood but refused (unknown vehicle/route, etc.). */
    data class Rejected(val message: String) : SignOnResult()
    data class NetworkError(val message: String) : SignOnResult()
}

/** Outcome of a sign-off attempt. */
sealed class SignOffResult {
    /** Shift closed; [closed] is how many were closed (0 = nothing was open, still fine). */
    data class Success(val closed: Int) : SignOffResult()
    object Unauthorized : SignOffResult()
    data class NetworkError(val message: String) : SignOffResult()
}

/** Outcome of a single position ping. */
sealed class PingResult {
    data class Success(val positionId: Int) : PingResult()
    /** 401 — token revoked/expired; caller should stop and send the user to login. */
    object Unauthorized : PingResult()
    /** 409 no_open_shift — the shift was closed server-side; stop tracking. */
    object NoOpenShift : PingResult()
    /** 422 — bad/missing fields; surfaced for logging, loop keeps going. */
    data class Rejected(val message: String) : PingResult()
    /** Transient: couldn't reach the server. Loop should show "reconnecting" and retry. */
    data class NetworkError(val message: String) : PingResult()
}

/**
 * Thin OkHttp wrapper for the PSV API. Calls are BLOCKING — invoke them off the
 * main thread (Dispatchers.IO).
 */
class ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** POST /api/driver-login.php */
    fun login(baseUrl: String, username: String, password: String): LoginResult {
        val base = try {
            normalizeBaseUrl(baseUrl)
        } catch (e: UrlException) {
            return LoginResult.NetworkError(e.message!!)
        }

        val payload = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()

        val request = Request.Builder()
            .url("$base/api/driver-login.php")
            .post(payload.toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()

                if (response.code == 401) return LoginResult.InvalidCredentials
                if (!response.isSuccessful) {
                    return LoginResult.NetworkError("Server error (${response.code}). Try again.")
                }

                val json = parseJson(bodyText)
                    ?: return LoginResult.NetworkError("Unexpected response from server.")

                if (json.optBoolean("ok", false)) {
                    val token = json.optString("token", "")
                    val driver = json.optString("driver", "")
                    if (token.isEmpty()) {
                        LoginResult.NetworkError("Server didn't return a token.")
                    } else {
                        LoginResult.Success(token, driver)
                    }
                } else {
                    // ok:false on a 2xx — treat as bad credentials per the contract.
                    LoginResult.InvalidCredentials
                }
            }
        } catch (e: IOException) {
            LoginResult.NetworkError(UNREACHABLE)
        }
    }

    /** GET /api/driver-vehicles.php (Bearer) */
    fun driverVehicles(baseUrl: String, token: String): VehiclesResult {
        val base = try {
            normalizeBaseUrl(baseUrl)
        } catch (e: UrlException) {
            return VehiclesResult.NetworkError(e.message!!)
        }

        val request = Request.Builder()
            .url("$base/api/driver-vehicles.php")
            .get()
            .addHeader("Authorization", "Bearer $token")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()

                if (response.code == 401) return VehiclesResult.Unauthorized
                if (!response.isSuccessful) {
                    return VehiclesResult.NetworkError("Server error (${response.code}). Try again.")
                }

                val json = parseJson(bodyText)
                    ?: return VehiclesResult.NetworkError("Unexpected response from server.")
                if (!json.optBoolean("ok", false)) {
                    return VehiclesResult.NetworkError("Unexpected response from server.")
                }

                val vehicles = mutableListOf<Vehicle>()
                json.optJSONArray("vehicles")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        vehicles.add(
                            Vehicle(
                                id = o.optInt("id"),
                                registration = o.optString("registration"),
                                label = o.optString("label"),
                                capacity = o.optInt("capacity")
                            )
                        )
                    }
                }

                val routes = mutableListOf<Route>()
                json.optJSONArray("routes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        routes.add(
                            Route(
                                id = o.optInt("id"),
                                routeNumber = o.optString("route_number"),
                                name = o.optString("name")
                            )
                        )
                    }
                }

                VehiclesResult.Success(vehicles, routes, json.optInt("speed_limit_kmh", 0))
            }
        } catch (e: IOException) {
            VehiclesResult.NetworkError(UNREACHABLE)
        }
    }

    /** POST /api/signon.php (Bearer) */
    fun signOn(baseUrl: String, token: String, vehicleId: Int, routeId: Int): SignOnResult {
        val base = try {
            normalizeBaseUrl(baseUrl)
        } catch (e: UrlException) {
            return SignOnResult.NetworkError(e.message!!)
        }

        val payload = JSONObject()
            .put("vehicle_id", vehicleId)
            .put("route_id", routeId)
            .toString()

        val request = Request.Builder()
            .url("$base/api/signon.php")
            .post(payload.toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $token")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()

                if (response.code == 401) return SignOnResult.Unauthorized
                // 422 unknown/missing vehicle or route, 409 conflict — surface the
                // server's own reason plainly.
                if (response.code == 422 || response.code == 409) {
                    return SignOnResult.Rejected(
                        serverError(bodyText, "Sign-on was rejected by the server.")
                    )
                }
                if (!response.isSuccessful) {
                    return SignOnResult.NetworkError("Server error (${response.code}). Try again.")
                }

                val json = parseJson(bodyText)
                    ?: return SignOnResult.NetworkError("Unexpected response from server.")

                if (json.optBoolean("ok", false)) {
                    SignOnResult.Success(json.optInt("shift_id"), json.optString("driver"))
                } else {
                    SignOnResult.Rejected(serverError(bodyText, "Sign-on was rejected by the server."))
                }
            }
        } catch (e: IOException) {
            SignOnResult.NetworkError(UNREACHABLE)
        }
    }

    /** POST /api/signoff.php (Bearer). Any ok:true is success (closed:0 is fine). */
    fun signOff(baseUrl: String, token: String, shiftId: Int): SignOffResult {
        val base = try {
            normalizeBaseUrl(baseUrl)
        } catch (e: UrlException) {
            return SignOffResult.NetworkError(e.message!!)
        }

        val payload = JSONObject().put("shift_id", shiftId).toString()

        val request = Request.Builder()
            .url("$base/api/signoff.php")
            .post(payload.toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $token")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()

                if (response.code == 401) return SignOffResult.Unauthorized
                if (!response.isSuccessful) {
                    return SignOffResult.NetworkError("Server error (${response.code}).")
                }

                val json = parseJson(bodyText)
                if (json != null && json.optBoolean("ok", false)) {
                    SignOffResult.Success(json.optInt("closed", 0))
                } else {
                    SignOffResult.NetworkError("Unexpected response from server.")
                }
            }
        } catch (e: IOException) {
            SignOffResult.NetworkError(UNREACHABLE)
        }
    }

    /**
     * POST /api/speeding-event.php {action:"open"} (Bearer). Fire-and-forget from
     * the caller: returns the new event_id, or null on any failure (incl. 401).
     */
    fun speedingOpen(
        baseUrl: String,
        token: String,
        shiftId: Int,
        peakKmh: Int,
        limitKmh: Int,
        startedAt: String,
    ): Int? {
        val base = try {
            normalizeBaseUrl(baseUrl)
        } catch (e: UrlException) {
            return null
        }

        val payload = JSONObject()
            .put("action", "open")
            .put("shift_id", shiftId)
            .put("peak_speed_kmh", peakKmh)
            .put("speed_limit_kmh", limitKmh)
            .put("started_at", startedAt)
            .toString()

        val request = Request.Builder()
            .url("$base/api/speeding-event.php")
            .post(payload.toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $token")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = parseJson(response.body?.string().orEmpty())
                if (json != null && json.optBoolean("ok", false) && json.has("event_id")) {
                    json.optInt("event_id")
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * POST /api/speeding-event.php {action:"close"} (Bearer). Fire-and-forget:
     * returns true on ok:true, false on any failure (incl. 401). A missed close is
     * fine server-side (the event stays "ongoing").
     */
    fun speedingClose(
        baseUrl: String,
        token: String,
        eventId: Int,
        peakKmh: Int,
        endedAt: String,
    ): Boolean {
        val base = try {
            normalizeBaseUrl(baseUrl)
        } catch (e: UrlException) {
            return false
        }

        val payload = JSONObject()
            .put("action", "close")
            .put("event_id", eventId)
            .put("peak_speed_kmh", peakKmh)
            .put("ended_at", endedAt)
            .toString()

        val request = Request.Builder()
            .url("$base/api/speeding-event.php")
            .post(payload.toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $token")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val json = parseJson(response.body?.string().orEmpty())
                json != null && json.optBoolean("ok", false)
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * POST /api/ping.php (Bearer). Optional fields are sent only when provided.
     * Returns 201 -> Success(position_id).
     */
    fun ping(
        baseUrl: String,
        token: String,
        shiftId: Int,
        lat: Double,
        lng: Double,
        speed: Float?,
        heading: Int?,
        seatStatus: String,
        recordedAt: String,
    ): PingResult {
        val base = try {
            normalizeBaseUrl(baseUrl)
        } catch (e: UrlException) {
            return PingResult.NetworkError(e.message!!)
        }

        val body = JSONObject()
            .put("shift_id", shiftId)
            .put("lat", lat)
            .put("lng", lng)
            .put("seat_status", seatStatus)
            .put("recorded_at", recordedAt)
        if (speed != null) body.put("speed", speed.toDouble())
        if (heading != null) body.put("heading", heading)

        val request = Request.Builder()
            .url("$base/api/ping.php")
            .post(body.toString().toRequestBody(JSON))
            .addHeader("Authorization", "Bearer $token")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()

                when (response.code) {
                    401 -> return PingResult.Unauthorized
                    409 -> return PingResult.NoOpenShift
                    422 -> return PingResult.Rejected(serverError(bodyText, "Ping rejected by server."))
                }
                if (!response.isSuccessful) {
                    return PingResult.NetworkError("Server error (${response.code}).")
                }

                val json = parseJson(bodyText)
                if (json != null && json.optBoolean("ok", false)) {
                    PingResult.Success(json.optInt("position_id"))
                } else {
                    PingResult.NetworkError("Unexpected response from server.")
                }
            }
        } catch (e: IOException) {
            PingResult.NetworkError(UNREACHABLE)
        }
    }

    // ---- Shared helpers ------------------------------------------------------

    private class UrlException(message: String) : Exception(message)

    /** Trim trailing slashes and require a valid http/https URL, else [UrlException]. */
    private fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val parsed = try {
            URL(trimmed)
        } catch (e: MalformedURLException) {
            throw UrlException("Server address isn't a valid URL.")
        }
        if (parsed.protocol != "http" && parsed.protocol != "https") {
            throw UrlException("Server address must start with http:// or https://")
        }
        return trimmed
    }

    private fun parseJson(body: String): JSONObject? =
        try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }

    /** Pull the server's `error` field (e.g. "unknown_vehicle") and make it readable. */
    private fun serverError(body: String, fallback: String): String {
        val raw = parseJson(body)?.optString("error", "").orEmpty()
        if (raw.isBlank()) return fallback
        return raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val UNREACHABLE = "Can't reach the server. Check the address and your network."
    }
}
