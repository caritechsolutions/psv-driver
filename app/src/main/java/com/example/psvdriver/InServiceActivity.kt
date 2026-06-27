package com.example.psvdriver

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// =========================== Tracking tunables ===============================
/** Ping cadence while the vehicle is moving. */
private const val MOVING_PING_INTERVAL_MS = 5_000L
/** Ping cadence while stationary — the floor, so a parked vehicle still reports. */
private const val STATIONARY_PING_INTERVAL_MS = 25_000L
/** At or above this speed (m/s, ~5.4 km/h) the vehicle counts as moving. */
private const val MOVING_SPEED_MPS = 1.5f
/** Or: moved at least this far (metres) since the last ping => moving. */
private const val MOVING_DISPLACEMENT_M = 15f
/** Fused location requested cadence and fastest allowed update. */
private const val LOCATION_UPDATE_INTERVAL_MS = 3_000L
private const val LOCATION_MIN_UPDATE_MS = 2_000L
/** Ignore a GPS fix older than this (treat as "no fix yet"). */
private const val STALE_FIX_MS = 60_000L
/** How often the on-screen stats (timer, "updated Ns ago") refresh. */
private const val UI_TICK_MS = 1_000L

// --- Speeding alarm tunables ---
/** Speed must stay at/over the limit this long before the alarm triggers (anti-spike). */
private const val SPEED_ALARM_SUSTAIN_MS = 4_000L
/** Hysteresis: only clear once speed drops this many km/h BELOW the limit. */
private const val SPEED_ALARM_CLEAR_MARGIN_KMH = 2
/** Gap between beeps while alarming (repeating, not a constant blare). */
private const val BEEP_REPEAT_INTERVAL_MS = 1_500L
/** Length of each beep. */
private const val BEEP_TONE_MS = 350
/** ToneGenerator volume (0-100). */
private const val ALARM_TONE_VOLUME = 100
/** Repeating vibration pattern while alarming: wait, buzz, gap (ms), looped. */
private val ALARM_VIBE_PATTERN = longArrayOf(0L, 500L, 1_000L)
// =============================================================================

class InServiceActivity : AppCompatActivity() {

    private val api = ApiClient()
    private lateinit var settings: SettingsStore
    private lateinit var tokens: TokenStore

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest

    private lateinit var map: MapView
    private lateinit var marker: Marker
    private var centeredOnce = false

    private lateinit var header: View
    private lateinit var routeVehicleText: TextView
    private lateinit var shiftText: TextView
    private lateinit var liveDot: View
    private lateinit var liveLabel: TextView
    private lateinit var permissionGroup: View
    private lateinit var permissionText: TextView
    private lateinit var permissionButton: Button
    private lateinit var trackingGroup: View
    private lateinit var speedGauge: SpeedGaugeView
    private lateinit var onShiftValue: TextView
    private lateinit var updatedText: TextView
    private lateinit var seatSwitch: SwitchMaterial
    private lateinit var signOffButton: Button
    private var liveAnim: android.animation.ObjectAnimator? = null

    // Speeding alarm state (main thread).
    private var speedLimitKmh = 0          // 0 => alarm disabled
    private var alarmActive = false
    private var overSinceElapsed = 0L      // elapsedRealtime when speed first reached the limit; 0 = not over
    private var beepJob: Job? = null
    private var toneGenerator: ToneGenerator? = null
    private lateinit var vibrator: Vibrator

    // Speeding-event reporting (server). Fire-and-forget; never blocks the alarm.
    private var alarmPeakKmh = 0           // running peak since the alarm entered
    private var speedingEventId: Int? = null   // event_id from the OPEN call, for the CLOSE
    private var alarmGen = 0               // bumped on every enter/clear; guards a late async OPEN

    // Live tracking state (read/written on the main thread).
    private var lastLocation: Location? = null
    private var lastPingLocation: Location? = null
    private var lastPingElapsed = 0L      // SystemClock.elapsedRealtime() of last OK ping; 0 = none yet
    private var reconnecting = false
    private var isTracking = false
    private var pingJob: Job? = null
    private var tickerJob: Job? = null

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onNewLocation(it) }
        }
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startTrackingIfReady() else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = SettingsStore(this)
        tokens = TokenStore(this)

        // Guard: only valid with a token + an open shift.
        if (!tokens.isLoggedIn) {
            goToLogin(null)
            return
        }
        if (tokens.shiftId == null) {
            goToSignOn()
            return
        }

        // osmdroid must be configured before the MapView is inflated. Use internal
        // cache so no storage permission is needed; user-agent or OSM blocks tiles.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }

        // Foreground-only tracking pauses when the screen sleeps, so keep it on
        // while this screen is showing. Cleared in onDestroy.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        speedLimitKmh = tokens.shiftSpeedLimitKmh
        vibrator = obtainVibrator()

        enableEdgeToEdge()
        setContentView(R.layout.activity_in_service)

        header = findViewById(R.id.header)
        routeVehicleText = findViewById(R.id.routeVehicleText)
        shiftText = findViewById(R.id.shiftText)
        liveDot = findViewById(R.id.liveDot)
        liveLabel = findViewById(R.id.liveLabel)
        permissionGroup = findViewById(R.id.permissionGroup)
        permissionText = findViewById(R.id.permissionText)
        permissionButton = findViewById(R.id.permissionButton)
        trackingGroup = findViewById(R.id.trackingGroup)
        speedGauge = findViewById(R.id.speedGauge)
        onShiftValue = findViewById(R.id.onShiftValue)
        updatedText = findViewById(R.id.updatedText)
        seatSwitch = findViewById(R.id.seatSwitch)
        signOffButton = findViewById(R.id.signOffButton)

        // The green header draws behind the status bar: top inset pads the header,
        // the remaining insets pad the scroll content.
        val headerPad = header.paddingTop
        val root = findViewById<View>(R.id.main)
        val rootPad = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, 0, bars.right, rootPad + bars.bottom)
            header.setPadding(
                header.paddingLeft, headerPad + bars.top, header.paddingRight, header.paddingBottom
            )
            insets
        }

        routeVehicleText.text = getString(
            R.string.route_vehicle_line, tokens.shiftRoute.orEmpty(), tokens.shiftVehicle.orEmpty()
        )
        shiftText.text = getString(R.string.shift_number, tokens.shiftId ?: 0)
        speedGauge.setLimit(speedLimitKmh)

        seatSwitch.setOnCheckedChangeListener { _, checked ->
            seatSwitch.setText(if (checked) R.string.seats_available else R.string.seats_full)
        }
        signOffButton.setOnClickListener { signOff() }

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS
        ).setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_MS).build()

        setupMap()
    }

    // ---- Map -----------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)
        marker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = getString(R.string.in_service_header)
        }
        map.overlays.add(marker)

        // Let the map pan/zoom without the surrounding ScrollView stealing the gesture.
        map.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false // don't consume — let the map handle the touch
        }
    }

    // ---- Lifecycle: tracking runs only while this screen is in the foreground --

    override fun onStart() {
        super.onStart()
        if (::map.isInitialized) map.onResume()
        startTrackingIfReady()
    }

    override fun onStop() {
        stopTracking()
        if (::map.isInitialized) map.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        // Final safety net: no zombie tone/vibration, release resources, drop the flag.
        stopBeep()
        stopVibration()
        toneGenerator?.release()
        toneGenerator = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startTrackingIfReady() {
        if (!::map.isInitialized) return // guard activity that bailed in onCreate
        if (!hasLocationPermission()) {
            showPermissionRationale()
            return
        }
        permissionGroup.visibility = View.GONE
        trackingGroup.visibility = View.VISIBLE

        if (isTracking) return
        isTracking = true
        startLocationUpdates()
        startLoops()
    }

    @SuppressLint("MissingPermission") // guarded by hasLocationPermission() before call
    private fun startLocationUpdates() {
        fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun stopTracking() {
        isTracking = false
        if (::fusedClient.isInitialized) fusedClient.removeLocationUpdates(locationCallback)
        pingJob?.cancel()
        tickerJob?.cancel()
        pingJob = null
        tickerJob = null
        if (::liveDot.isInitialized) stopLiveAnim()
        // Leaving/backgrounding the screen: drop any active alarm cleanly.
        if (::vibrator.isInitialized) clearAlarm()
    }

    private fun onNewLocation(location: Location) {
        lastLocation = location
        val hasSpeed = location.hasSpeed()
        val kmh = if (hasSpeed) (location.speed * 3.6f).roundToInt() else 0
        speedGauge.setSpeed(kmh)
        evaluateSpeedAlarm(kmh, hasSpeed)

        val point = GeoPoint(location.latitude, location.longitude)
        marker.position = point
        if (!centeredOnce) {
            map.controller.setCenter(point)
            centeredOnce = true
        } else {
            map.controller.animateTo(point)
        }
        map.invalidate()
    }

    // ---- Speeding alarm ------------------------------------------------------

    /** Sustained-over-limit entry with hysteresis on exit. Compares integer km/h. */
    private fun evaluateSpeedAlarm(kmh: Int, hasSpeed: Boolean) {
        if (speedLimitKmh <= 0 || !hasSpeed) return // disabled, or can't judge this fix
        val now = SystemClock.elapsedRealtime()
        if (!alarmActive) {
            if (kmh >= speedLimitKmh) {
                if (overSinceElapsed == 0L) {
                    overSinceElapsed = now
                } else if (now - overSinceElapsed >= SPEED_ALARM_SUSTAIN_MS) {
                    alarmPeakKmh = kmh
                    enterAlarm()
                }
            } else {
                overSinceElapsed = 0L
            }
        } else {
            alarmPeakKmh = maxOf(alarmPeakKmh, kmh) // running peak during the event
            if (kmh <= speedLimitKmh - SPEED_ALARM_CLEAR_MARGIN_KMH) clearAlarm()
        }
    }

    private fun enterAlarm() {
        if (alarmActive) return
        alarmActive = true
        // The gauge already shows red the instant speed crosses the limit; the
        // sustained alarm only adds the beep + vibration.
        startBeep()
        startVibration()
        reportSpeedingOpen(alarmPeakKmh)
    }

    private fun clearAlarm() {
        overSinceElapsed = 0L
        if (!alarmActive) return
        alarmActive = false
        stopBeep()
        stopVibration()
        reportSpeedingClose()
    }

    // ---- Speeding-event reporting (fire-and-forget; never blocks the alarm) ----

    /** OPEN on enter. event_id is stored only if its generation still matches. */
    private fun reportSpeedingOpen(peakKmh: Int) {
        val gen = ++alarmGen
        speedingEventId = null
        val token = tokens.token ?: return
        val shiftId = tokens.shiftId ?: return
        val baseUrl = settings.baseUrl
        val limit = speedLimitKmh
        val startedAt = isoFormat.format(Date())
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val id = runCatching {
                ApiClient().speedingOpen(baseUrl, token, shiftId, peakKmh, limit, startedAt)
            }.getOrNull()
            if (id != null) withContext(Dispatchers.Main) {
                // Only adopt the id if we're still in the same alarm event.
                if (gen == alarmGen) speedingEventId = id
            }
        }
    }

    /** CLOSE on clear (incl. teardown while alarming). Skipped if OPEN gave no id. */
    private fun reportSpeedingClose() {
        val eventId = speedingEventId
        val peakKmh = alarmPeakKmh
        speedingEventId = null
        alarmGen++ // invalidate any still-in-flight OPEN from this event
        if (eventId == null) return
        val token = tokens.token ?: return
        val baseUrl = settings.baseUrl
        val endedAt = isoFormat.format(Date())
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { ApiClient().speedingClose(baseUrl, token, eventId, peakKmh, endedAt) }
        }
    }

    private fun startBeep() {
        if (beepJob != null) return
        beepJob = lifecycleScope.launch {
            // STREAM_ALARM so a safety beep sounds even on silent/vibrate.
            val tg = toneGenerator ?: try {
                ToneGenerator(AudioManager.STREAM_ALARM, ALARM_TONE_VOLUME).also { toneGenerator = it }
            } catch (e: RuntimeException) {
                null
            }
            while (isActive) {
                tg?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, BEEP_TONE_MS)
                delay(BEEP_REPEAT_INTERVAL_MS)
            }
        }
    }

    private fun stopBeep() {
        beepJob?.cancel()
        beepJob = null
        toneGenerator?.stopTone()
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(ALARM_VIBE_PATTERN, 0)) // repeat from index 0
        } else {
            vibrator.vibrate(ALARM_VIBE_PATTERN, 0)
        }
    }

    private fun stopVibration() {
        if (::vibrator.isInitialized) vibrator.cancel()
    }

    private fun obtainVibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    // ---- Ping loop (adaptive cadence) ----------------------------------------

    private fun startLoops() {
        pingJob = lifecycleScope.launch { pingLoop(this) }
        tickerJob = lifecycleScope.launch { uiTicker(this) }
    }

    private suspend fun pingLoop(scope: CoroutineScope) {
        val token = tokens.token ?: return goToLogin(getString(R.string.error_session_expired))
        val shiftId = tokens.shiftId ?: return

        while (scope.isActive) {
            val loc = freshLocationOrNull()
            if (loc == null) {
                // No usable fix yet — wait briefly and re-check.
                delay(LOCATION_MIN_UPDATE_MS)
                continue
            }

            val moving = isMoving(loc)
            val seat = if (seatSwitch.isChecked) "available" else "full"
            // Location.getSpeed() is m/s; send km/h to match what the screen shows
            // and what the server/rider app expects.
            val speed = if (loc.hasSpeed()) loc.speed * 3.6f else null
            val heading = if (loc.hasBearing()) loc.bearing.roundToInt().mod(360) else null

            val result = withContext(Dispatchers.IO) {
                api.ping(
                    settings.baseUrl, token, shiftId,
                    loc.latitude, loc.longitude, speed, heading, seat, isoFormat.format(Date(loc.time))
                )
            }
            lastPingLocation = loc

            when (result) {
                is PingResult.Success -> {
                    reconnecting = false
                    lastPingElapsed = SystemClock.elapsedRealtime()
                }
                is PingResult.Unauthorized -> {
                    stopTracking()
                    sessionExpired()
                    return
                }
                is PingResult.NoOpenShift -> {
                    stopTracking()
                    shiftClosed()
                    return
                }
                // 422 and transient network errors: keep trying, show reconnecting.
                is PingResult.Rejected -> reconnecting = true
                is PingResult.NetworkError -> reconnecting = true
            }

            delay(if (moving) MOVING_PING_INTERVAL_MS else STATIONARY_PING_INTERVAL_MS)
        }
    }

    /** The latest fix, or null if we have none or it's too stale to trust. */
    private fun freshLocationOrNull(): Location? {
        val loc = lastLocation ?: return null
        val age = System.currentTimeMillis() - loc.time
        return if (age in 0..STALE_FIX_MS) loc else null
    }

    private fun isMoving(loc: Location): Boolean {
        if (loc.hasSpeed() && loc.speed >= MOVING_SPEED_MPS) return true
        val since = lastPingLocation ?: return false
        return loc.distanceTo(since) >= MOVING_DISPLACEMENT_M
    }

    // ---- Stats ticker --------------------------------------------------------

    private suspend fun uiTicker(scope: CoroutineScope) {
        while (scope.isActive) {
            onShiftValue.text = elapsedOnShift()
            updatedText.text = when {
                reconnecting -> getString(R.string.status_reconnecting)
                lastPingElapsed == 0L -> getString(R.string.status_waiting_fix)
                else -> {
                    val secs = ((SystemClock.elapsedRealtime() - lastPingElapsed) / 1000L).toInt()
                    if (secs <= 0) getString(R.string.status_updated_now)
                    else getString(R.string.status_updated_ago, secs)
                }
            }
            // Live indicator reflects the SAME ping success/failure state.
            setLiveIndicator(live = !reconnecting)
            delay(UI_TICK_MS)
        }
    }

    /** Green pulsing "Live" while pings succeed; muted amber "Reconnecting…" on failure. */
    private fun setLiveIndicator(live: Boolean) {
        if (live) {
            liveLabel.setText(R.string.live_label)
            liveLabel.setTextColor(ContextCompat.getColor(this, R.color.ps_on_green))
            liveDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.live_green)
            if (liveAnim == null) {
                liveAnim = android.animation.ObjectAnimator.ofFloat(liveDot, View.ALPHA, 1f, 0.25f).apply {
                    duration = 700L
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            liveLabel.setText(R.string.status_reconnecting)
            liveLabel.setTextColor(ContextCompat.getColor(this, R.color.ps_on_green_muted))
            liveDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.ps_amber)
            stopLiveAnim()
        }
    }

    private fun stopLiveAnim() {
        liveAnim?.cancel()
        liveAnim = null
        liveDot.alpha = 1f
    }

    private fun elapsedOnShift(): String {
        val started = tokens.shiftStartedAt
        if (started <= 0L) return "—"
        var secs = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(0)
        val h = secs / 3600; secs %= 3600
        val m = secs / 60; val s = secs % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    // ---- Permission UI -------------------------------------------------------

    private fun showPermissionRationale() {
        trackingGroup.visibility = View.GONE
        permissionGroup.visibility = View.VISIBLE
        permissionText.text = getString(R.string.perm_rationale)
        permissionButton.setText(R.string.action_grant_location)
        permissionButton.setOnClickListener {
            locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun showPermissionDenied() {
        trackingGroup.visibility = View.GONE
        permissionGroup.visibility = View.VISIBLE
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Soft denial — let them try again.
            permissionText.text = getString(R.string.perm_denied)
            permissionButton.setText(R.string.action_grant_location)
            permissionButton.setOnClickListener {
                locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else {
            // "Don't ask again" — can only be changed in Settings.
            permissionText.text = getString(R.string.perm_denied_settings)
            permissionButton.setText(R.string.action_open_settings)
            permissionButton.setOnClickListener { openAppSettings() }
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    // ---- Exit paths ----------------------------------------------------------

    private fun signOff() {
        // Fire the server sign-off but never block the driver from leaving. Captured
        // before clearShift(); runs on a detached scope so it survives this activity
        // finishing. Failures/401 are ignored — signon auto-closes a stale shift, so
        // a missed signoff self-heals.
        val token = tokens.token
        val shiftId = tokens.shiftId
        val baseUrl = settings.baseUrl
        if (token != null && shiftId != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { ApiClient().signOff(baseUrl, token, shiftId) }
            }
        }

        stopTracking()
        tokens.clearShift()
        goToSignOn()
    }

    private fun shiftClosed() {
        tokens.clearShift()
        Toast.makeText(this, R.string.status_shift_closed, Toast.LENGTH_LONG).show()
        goToSignOn()
    }

    private fun sessionExpired() {
        tokens.clear()
        goToLogin(getString(R.string.error_session_expired))
    }

    private fun goToSignOn() {
        startActivity(Intent(this, SignOnActivity::class.java))
        finish()
    }

    private fun goToLogin(message: String?) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (message != null) intent.putExtra(MainActivity.EXTRA_MESSAGE, message)
        startActivity(intent)
        finish()
    }
}
