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

/** Outcome of a login attempt. */
sealed class LoginResult {
    data class Success(val token: String, val driver: String) : LoginResult()
    /** Server returned 401 invalid_credentials (generic; no enumeration). */
    object InvalidCredentials : LoginResult()
    /** Could not reach the server, bad URL, timeout, or an unexpected response. */
    data class NetworkError(val message: String) : LoginResult()
}

/**
 * Thin OkHttp wrapper for the PSV API. Only login is implemented in this step.
 * Calls are BLOCKING — invoke them off the main thread (Dispatchers.IO).
 */
class ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** POST /api/driver-login.php */
    fun login(baseUrl: String, username: String, password: String): LoginResult {
        val trimmed = baseUrl.trim().trimEnd('/')

        // Validate the URL up front so a typo gives a clear message instead of a crash.
        try {
            val parsed = URL(trimmed)
            if (parsed.protocol != "http" && parsed.protocol != "https") {
                return LoginResult.NetworkError("Server address must start with http:// or https://")
            }
        } catch (e: MalformedURLException) {
            return LoginResult.NetworkError("Server address isn't a valid URL.")
        }

        val payload = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()

        val request = Request.Builder()
            .url("$trimmed/api/driver-login.php")
            .post(payload.toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()

                if (response.code == 401) {
                    return LoginResult.InvalidCredentials
                }
                if (!response.isSuccessful) {
                    return LoginResult.NetworkError("Server error (${response.code}). Try again.")
                }

                val json = try {
                    JSONObject(bodyText)
                } catch (e: Exception) {
                    return LoginResult.NetworkError("Unexpected response from server.")
                }

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
            LoginResult.NetworkError("Can't reach the server. Check the address and your network.")
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
