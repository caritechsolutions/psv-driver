package com.example.psvdriver

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val api = ApiClient()
    private lateinit var settings: SettingsStore
    private lateinit var tokens: TokenStore

    private lateinit var loginGroup: View
    private lateinit var loggedInGroup: View
    private lateinit var serverUrlInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    private lateinit var loggedInText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        settings = SettingsStore(this)
        tokens = TokenStore(this)

        loginGroup = findViewById(R.id.loginGroup)
        loggedInGroup = findViewById(R.id.loggedInGroup)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        logoutButton = findViewById(R.id.logoutButton)
        loggedInText = findViewById(R.id.loggedInText)
        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)

        serverUrlInput.setText(settings.baseUrl)
        loginButton.setOnClickListener { attemptLogin() }
        logoutButton.setOnClickListener { logout() }

        render()
    }

    /** Show either the login form or the logged-in state based on stored token. */
    private fun render() {
        if (tokens.isLoggedIn) {
            loginGroup.visibility = View.GONE
            loggedInGroup.visibility = View.VISIBLE
            loggedInText.text = getString(R.string.logged_in_as, tokens.driver.orEmpty())
        } else {
            loginGroup.visibility = View.VISIBLE
            loggedInGroup.visibility = View.GONE
        }
    }

    private fun attemptLogin() {
        val baseUrl = serverUrlInput.text?.toString()?.trim().orEmpty()
        val username = usernameInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()

        if (username.isEmpty() || password.isEmpty()) {
            statusText.text = getString(R.string.error_missing_fields)
            return
        }

        // Persist the (possibly edited) server URL so it survives restarts.
        settings.baseUrl = baseUrl

        setBusy(true)
        statusText.text = getString(R.string.status_logging_in)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.login(baseUrl, username, password)
            }
            setBusy(false)
            when (result) {
                is LoginResult.Success -> {
                    tokens.save(result.token, result.driver)
                    passwordInput.text?.clear()
                    statusText.text = ""
                    render()
                }
                is LoginResult.InvalidCredentials -> {
                    statusText.text = getString(R.string.error_invalid_credentials)
                }
                is LoginResult.NetworkError -> {
                    statusText.text = result.message
                }
            }
        }
    }

    private fun logout() {
        tokens.clear()
        statusText.text = ""
        usernameInput.text?.clear()
        passwordInput.text?.clear()
        render()
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        loginButton.isEnabled = !busy
    }
}
