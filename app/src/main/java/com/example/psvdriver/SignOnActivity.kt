package com.example.psvdriver

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignOnActivity : AppCompatActivity() {

    private val api = ApiClient()
    private lateinit var settings: SettingsStore
    private lateinit var tokens: TokenStore

    /** Global speed limit from the driver-vehicles fetch; stored with the shift on sign-on. */
    private var speedLimitKmh = 0

    private lateinit var headerText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var formGroup: View
    private lateinit var vehicleSpinner: Spinner
    private lateinit var routeSpinner: Spinner
    private lateinit var goInServiceButton: Button
    private lateinit var emptyText: TextView
    private lateinit var retryButton: Button
    private lateinit var statusText: TextView
    private lateinit var logoutButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = SettingsStore(this)
        tokens = TokenStore(this)

        // No token (e.g. cleared elsewhere) -> back to login.
        if (!tokens.isLoggedIn) {
            goToLogin()
            return
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_sign_on)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        headerText = findViewById(R.id.headerText)
        progress = findViewById(R.id.progress)
        formGroup = findViewById(R.id.formGroup)
        vehicleSpinner = findViewById(R.id.vehicleSpinner)
        routeSpinner = findViewById(R.id.routeSpinner)
        goInServiceButton = findViewById(R.id.goInServiceButton)
        emptyText = findViewById(R.id.emptyText)
        retryButton = findViewById(R.id.retryButton)
        statusText = findViewById(R.id.statusText)
        logoutButton = findViewById(R.id.logoutButton)

        headerText.text = getString(R.string.title_sign_on)
        goInServiceButton.setOnClickListener { attemptSignOn() }
        retryButton.setOnClickListener { loadVehicles() }
        logoutButton.setOnClickListener { logout() }

        // If a shift is already open, go straight to the in-service screen.
        if (tokens.shiftId != null) {
            goToInService()
        } else {
            loadVehicles()
        }
    }

    // ---- Fetch vehicles + routes --------------------------------------------

    private fun loadVehicles() {
        showOnly(progress)
        statusText.text = ""
        val token = tokens.token ?: return goToLogin()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.driverVehicles(settings.baseUrl, token)
            }
            progress.visibility = View.GONE
            when (result) {
                is VehiclesResult.Success -> {
                    speedLimitKmh = result.speedLimitKmh
                    populate(result.vehicles, result.routes)
                }
                is VehiclesResult.Unauthorized -> sessionExpired()
                is VehiclesResult.NetworkError -> {
                    showOnly(retryButton)
                    statusText.text = result.message
                }
            }
        }
    }

    private fun populate(vehicles: List<Vehicle>, routes: List<Route>) {
        // Can't sign on without both a vehicle and a route.
        if (vehicles.isEmpty()) {
            emptyText.text = getString(R.string.empty_no_vehicles)
            showOnly(emptyText)
            return
        }
        if (routes.isEmpty()) {
            emptyText.text = getString(R.string.empty_no_routes)
            showOnly(emptyText)
            return
        }

        vehicleSpinner.adapter = spinnerAdapter(vehicles)
        routeSpinner.adapter = spinnerAdapter(routes)
        showOnly(formGroup)
    }

    private fun <T> spinnerAdapter(items: List<T>): ArrayAdapter<T> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    // ---- Sign on -------------------------------------------------------------

    private fun attemptSignOn() {
        val vehicle = vehicleSpinner.selectedItem as? Vehicle ?: return
        val route = routeSpinner.selectedItem as? Route ?: return
        val token = tokens.token ?: return goToLogin()

        setBusy(true)
        statusText.text = getString(R.string.status_signing_on)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.signOn(settings.baseUrl, token, vehicle.id, route.id)
            }
            setBusy(false)
            when (result) {
                is SignOnResult.Success -> {
                    tokens.saveShift(
                        result.shiftId,
                        vehicle.toString(),
                        route.toString(),
                        System.currentTimeMillis(),
                        speedLimitKmh
                    )
                    goToInService()
                }
                is SignOnResult.Unauthorized -> sessionExpired()
                is SignOnResult.Rejected -> statusText.text = result.message
                is SignOnResult.NetworkError -> statusText.text = result.message
            }
        }
    }

    // ---- Navigation / state --------------------------------------------------

    private fun goToInService() {
        startActivity(Intent(this, InServiceActivity::class.java))
        finish()
    }

    private fun logout() {
        tokens.clear()
        goToLogin()
    }

    private fun sessionExpired() {
        tokens.clear()
        // Surface why on the login screen we bounce to.
        goToLogin(getString(R.string.error_session_expired))
    }

    private fun goToLogin(message: String? = null) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (message != null) intent.putExtra(MainActivity.EXTRA_MESSAGE, message)
        startActivity(intent)
        finish()
    }

    /** Show exactly one of the mutually-exclusive content views; hide the rest. */
    private fun showOnly(view: View) {
        progress.visibility = if (view === progress) View.VISIBLE else View.GONE
        formGroup.visibility = if (view === formGroup) View.VISIBLE else View.GONE
        emptyText.visibility = if (view === emptyText) View.VISIBLE else View.GONE
        retryButton.visibility = if (view === retryButton) View.VISIBLE else View.GONE
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        goInServiceButton.isEnabled = !busy
    }
}
