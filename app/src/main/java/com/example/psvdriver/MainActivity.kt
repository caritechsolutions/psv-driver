package com.example.psvdriver

import android.content.Intent
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

    private lateinit var serverUrlInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = SettingsStore(this)
        tokens = TokenStore(this)

        // Already logged in -> skip straight to sign-on.
        if (tokens.isLoggedIn) {
            goToSignOn()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        serverUrlInput = findViewById(R.id.serverUrlInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)

        serverUrlInput.setText(settings.baseUrl)
        loginButton.setOnClickListener { attemptLogin() }

        // Shown when bounced here from sign-on (e.g. session expired).
        intent.getStringExtra(EXTRA_MESSAGE)?.let { statusText.text = it }
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
                    goToSignOn()
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

    private fun goToSignOn() {
        startActivity(Intent(this, SignOnActivity::class.java))
        finish()
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        loginButton.isEnabled = !busy
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
    }
}
