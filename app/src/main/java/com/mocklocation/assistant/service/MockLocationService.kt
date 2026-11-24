package com.mocklocation.assistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationProvider
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.mocklocation.assistant.MainActivity
import com.mocklocation.assistant.R
import com.mocklocation.assistant.util.CoordinateConverter
import com.mocklocation.assistant.util.LocationStore
import com.mocklocation.assistant.util.MockPermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * High-frequency (10 Hz) foreground service that injects mock GPS, Network, and Fused locations,
 * holds a partial wake lock + active LocationListener + optional floating overlay capsule to
 * prevent background throttling/freezing on aggressive OEM ROMs such as vivo OriginOS.
 */
class MockLocationService : Service() {

    data class MockState(
        val isRunning: Boolean = false,
        val wgsLat: Double = 0.0,
        val wgsLng: Double = 0.0,
        val label: String = "",
        val errorMessage: String? = null,
        val tickCount: Long = 0,
        val isWalking: Boolean = false,
        val walkSpeedMps: Float = 0f,
        val walkBearingDeg: Float = 0f
    )

    private lateinit var locationManager: LocationManager
    private lateinit var store: LocationStore
    private lateinit var mainHandler: Handler
    private var fusedClient: FusedLocationProviderClient? = null
    private val registeredProviders = mutableListOf<String>()

    private var tickerThread: HandlerThread? = null
    private var tickerHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayStatusTv: TextView? = null

    @Volatile
    private var isLoopActive: Boolean = false
    @Volatile
    private var currentLat: Double = LocationStore.DEFAULT_WGS_LAT
    @Volatile
    private var currentLng: Double = LocationStore.DEFAULT_WGS_LNG
    @Volatile
    private var currentLabel: String = LocationStore.DEFAULT_LABEL
    @Volatile
    private var enableJitter: Boolean = true
    @Volatile
    private var altitudeMeters: Double = 45.0
    @Volatile
    private var walkSpeedMps: Float = 0f
    @Volatile
    private var walkBearingDeg: Float = 0f

    private var jitterNorthMeters: Double = 0.0
    private var jitterEastMeters: Double = 0.0
    private var tickCounter: Long = 0L

    // Active listener keeps OEM location HAL / OriginOS scheduler aware of an ongoing client session
    private val keepAliveListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // No-op: keeps provider subscription active
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            // Override explicitly for compatibility across older/OEM Android ROMs
        }

        override fun onProviderEnabled(provider: String) {
            // No-op
        }

        override fun onProviderDisabled(provider: String) {
            // No-op
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isLoopActive) return
            val ok = pushLocationTick()
            if (ok && isLoopActive) {
                tickerHandler?.postDelayed(this, PUSH_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        store = LocationStore(this)
        mainHandler = Handler(Looper.getMainLooper())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        fusedClient = runCatching {
            LocationServices.getFusedLocationProviderClient(this)
        }.getOrNull()

        tickerThread = HandlerThread("MockLocationTicker", Process.THREAD_PRIORITY_FOREGROUND).apply {
            start()
        }
        tickerHandler = Handler(tickerThread!!.looper)

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MockLocation::TickerWakeLock")?.apply {
            setReferenceCounted(false)
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMocking(errorMessage = null)
                return START_NOT_STICKY
            }

            ACTION_SET_WALK -> {
                walkSpeedMps = intent.getFloatExtra(EXTRA_WALK_SPEED_MPS, 0f).coerceAtLeast(0f)
                walkBearingDeg = intent.getFloatExtra(EXTRA_WALK_BEARING_DEG, 0f)
                _state.value = _state.value.copy(
                    isWalking = walkSpeedMps > 0f,
                    walkSpeedMps = walkSpeedMps,
                    walkBearingDeg = walkBearingDeg
                )
                return START_STICKY
            }

            ACTION_REFRESH_OVERLAY -> {
                if (isLoopActive) {
                    syncOverlayState()
                }
                return START_STICKY
            }

            else -> {
                if (!MockPermissionHelper.hasLocationPermission(this)) {
                    _state.value = _state.value.copy(
                        isRunning = false,
                        errorMessage = getString(R.string.err_missing_location_permission)
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                currentLat = intent?.getDoubleExtra(EXTRA_WGS_LAT, store.getLastTarget().wgsLat)
                    ?: store.getLastTarget().wgsLat
                currentLng = intent?.getDoubleExtra(EXTRA_WGS_LNG, store.getLastTarget().wgsLng)
                    ?: store.getLastTarget().wgsLng
                currentLabel = intent?.getStringExtra(EXTRA_LABEL)
                    ?: store.getLastTarget().label
                enableJitter = intent?.getBooleanExtra(EXTRA_JITTER, store.isJitterEnabled())
                    ?: store.isJitterEnabled()
                altitudeMeters = store.getAltitudeMeters()

                if (intent?.hasExtra(EXTRA_WALK_SPEED_MPS) == true) {
                    walkSpeedMps = intent.getFloatExtra(EXTRA_WALK_SPEED_MPS, 0f).coerceAtLeast(0f)
                    walkBearingDeg = intent.getFloatExtra(EXTRA_WALK_BEARING_DEG, 0f)
                }

                startForegroundWithNotification()
                acquireWakeLock()

                if (!setupTestProviders()) {
                    _state.value = _state.value.copy(
                        isRunning = false,
                        errorMessage = getString(R.string.err_mock_permission_required)
                    )
                    releaseWakeLock()
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }

                isLoopActive = true
                tickCounter = _state.value.tickCount
                _state.value = MockState(
                    isRunning = true,
                    wgsLat = currentLat,
                    wgsLng = currentLng,
                    label = currentLabel,
                    errorMessage = null,
                    tickCount = tickCounter,
                    isWalking = walkSpeedMps > 0f,
                    walkSpeedMps = walkSpeedMps,
                    walkBearingDeg = walkBearingDeg
                )

                syncOverlayState()
                startMockLoopWithBurst()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isLoopActive = false
        tickerHandler?.removeCallbacksAndMessages(null)
        detachOverlay()
        teardownTestProviders()
        releaseWakeLock()
        tickerThread?.quitSafely()
        tickerThread = null
        tickerHandler = null
        if (_state.value.isRunning) {
            _state.value = _state.value.copy(isRunning = false, isWalking = false)
        }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(6L * 60L * 60L * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(currentLabel, currentLat, currentLng)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(label: String, lat: Double, lng: Double): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val coordText = String.format(Locale.US, "WGS-84: %.6f, %.6f", lat, lng)
        val contentTitle = if (walkSpeedMps > 0f) {
            getString(R.string.notification_title_walking, label)
        } else {
            getString(R.string.notification_title_active, label)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_pin)
            .setContentTitle(contentTitle)
            .setContentText(coordText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPendingIntent)
            .addAction(0, getString(R.string.action_stop_mock), stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(currentLabel, currentLat, currentLng))
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun setupTestProviders(): Boolean {
        teardownTestProviders()
        registeredProviders.clear()

        val gpsOk = registerProvider(
            name = LocationManager.GPS_PROVIDER,
            requiresNetwork = false,
            requiresSatellite = true,
            requiresCell = false,
            accuracyFine = true
        )
        if (!gpsOk) return false
        registeredProviders.add(LocationManager.GPS_PROVIDER)

        val netOk = registerProvider(
            name = LocationManager.NETWORK_PROVIDER,
            requiresNetwork = true,
            requiresSatellite = false,
            requiresCell = true,
            accuracyFine = true
        ) || registerProvider(
            name = LocationManager.NETWORK_PROVIDER,
            requiresNetwork = true,
            requiresSatellite = false,
            requiresCell = true,
            accuracyFine = false
        )
        if (netOk) {
            registeredProviders.add(LocationManager.NETWORK_PROVIDER)
        }

        if (registerProvider(
                name = FUSED_PROVIDER,
                requiresNetwork = true,
                requiresSatellite = true,
                requiresCell = false,
                accuracyFine = true
            )
        ) {
            registeredProviders.add(FUSED_PROVIDER)
        }

        runCatching {
            fusedClient?.setMockMode(true)?.addOnFailureListener { }
        }

        if (MockPermissionHelper.hasLocationPermission(this)) {
            val looper = tickerThread?.looper ?: Looper.getMainLooper()
            for (provider in registeredProviders) {
                if (provider == LocationManager.GPS_PROVIDER ||
                    provider == LocationManager.NETWORK_PROVIDER
                ) {
                    runCatching {
                        locationManager.requestLocationUpdates(
                            provider,
                            200L,
                            0f,
                            keepAliveListener,
                            looper
                        )
                    }
                }
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun registerProvider(
        name: String,
        requiresNetwork: Boolean,
        requiresSatellite: Boolean,
        requiresCell: Boolean,
        accuracyFine: Boolean
    ): Boolean {
        return try {
            runCatching { locationManager.removeTestProvider(name) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val props = ProviderProperties.Builder()
                    .setHasNetworkRequirement(requiresNetwork)
                    .setHasSatelliteRequirement(requiresSatellite)
                    .setHasCellRequirement(requiresCell)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(
                        if (accuracyFine) ProviderProperties.ACCURACY_FINE
                        else ProviderProperties.ACCURACY_COARSE
                    )
                    .build()
                locationManager.addTestProvider(name, props)
            } else {
                locationManager.addTestProvider(
                    name,
                    requiresNetwork,
                    requiresSatellite,
                    requiresCell,
                    false,
                    true,
                    true,
                    true,
                    Criteria.POWER_LOW,
                    if (accuracyFine) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
                )
            }
            locationManager.setTestProviderEnabled(name, true)
            setProviderAvailable(name)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun setProviderAvailable(provider: String) {
        runCatching {
            val extras = Bundle().apply { putInt("satellites", 16) }
            locationManager.setTestProviderStatus(
                provider,
                LocationProvider.AVAILABLE,
                extras,
                System.currentTimeMillis()
            )
        }
    }

    private fun teardownTestProviders() {
        runCatching {
            locationManager.removeUpdates(keepAliveListener)
        }
        for (provider in registeredProviders.toList()) {
            runCatching {
                locationManager.setTestProviderEnabled(provider, false)
            }
            runCatching {
                locationManager.removeTestProvider(provider)
            }
        }
        registeredProviders.clear()
        runCatching {
            fusedClient?.setMockMode(false)?.addOnFailureListener { }
        }
    }

    private fun startMockLoopWithBurst() {
        val handler = tickerHandler ?: return
        handler.removeCallbacksAndMessages(null)
        handler.post {
            if (!isLoopActive) return@post
            // Startup burst ensures getLastKnownLocation across all providers is fresh immediately
            repeat(3) { idx ->
                if (!isLoopActive) return@post
                if (!pushLocationTick()) return@post
                if (idx < 2) SystemClock.sleep(25L)
            }
            if (isLoopActive) {
                handler.postDelayed(tickRunnable, PUSH_INTERVAL_MS)
            }
        }
    }

    private fun pushLocationTick(): Boolean {
        return try {
            if (walkSpeedMps > 0f) {
                val stepDist = walkSpeedMps * (PUSH_INTERVAL_MS / 1000.0)
                val rad = Math.toRadians(walkBearingDeg.toDouble())
                val north = stepDist * cos(rad)
                val east = stepDist * sin(rad)
                val moved = CoordinateConverter.offsetMeters(
                    currentLat, currentLng, north, east
                )
                currentLat = moved.lat
                currentLng = moved.lng
            }

            val pushTarget = if (enableJitter && walkSpeedMps == 0f) {
                jitterNorthMeters = (jitterNorthMeters + (Random.nextDouble() - 0.5) * 0.06)
                    .coerceIn(-0.25, 0.25)
                jitterEastMeters = (jitterEastMeters + (Random.nextDouble() - 0.5) * 0.06)
                    .coerceIn(-0.25, 0.25)
                CoordinateConverter.offsetMeters(
                    currentLat, currentLng, jitterNorthMeters, jitterEastMeters
                )
            } else {
                CoordinateConverter.offsetMeters(currentLat, currentLng, 0.0, 0.0)
            }

            val acc = 1.0f + Random.nextFloat() * 0.3f
            val gpsLocation = buildMockLocation(
                provider = LocationManager.GPS_PROVIDER,
                lat = pushTarget.lat,
                lng = pushTarget.lng,
                accuracy = acc
            )

            for (provider in registeredProviders) {
                val loc = if (provider == LocationManager.GPS_PROVIDER) {
                    gpsLocation
                } else {
                    buildMockLocation(provider, pushTarget.lat, pushTarget.lng, acc)
                }
                locationManager.setTestProviderLocation(provider, loc)
            }

            runCatching {
                fusedClient?.setMockLocation(gpsLocation)?.addOnFailureListener { }
            }

            tickCounter++

            if (tickCounter % 10L == 0L) {
                for (provider in registeredProviders) {
                    setProviderAvailable(provider)
                }
            }

            if (tickCounter % 5L == 0L || tickCounter <= 3L) {
                _state.value = MockState(
                    isRunning = true,
                    wgsLat = currentLat,
                    wgsLng = currentLng,
                    label = currentLabel,
                    errorMessage = null,
                    tickCount = tickCounter,
                    isWalking = walkSpeedMps > 0f,
                    walkSpeedMps = walkSpeedMps,
                    walkBearingDeg = walkBearingDeg
                )
                updateOverlayTickText(tickCounter)
            }

            if (tickCounter % 20L == 0L) {
                updateNotification()
            }
            true
        } catch (_: SecurityException) {
            stopMocking(getString(R.string.err_mock_permission_revoked))
            false
        } catch (_: IllegalArgumentException) {
            if (!setupTestProviders()) {
                stopMocking(getString(R.string.err_mock_permission_revoked))
                false
            } else {
                true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun buildMockLocation(
        provider: String,
        lat: Double,
        lng: Double,
        accuracy: Float
    ): Location {
        return Location(provider).apply {
            latitude = lat
            longitude = lng
            altitude = altitudeMeters
            this.accuracy = accuracy
            bearing = walkBearingDeg
            speed = if (walkSpeedMps > 0f) walkSpeedMps else 0.02f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 1.5f
                speedAccuracyMetersPerSecond = 0.2f
                bearingAccuracyDegrees = 3.0f
            }
            extras = Bundle().apply {
                putInt("satellites", 16)
                putInt("maxCn0", 45)
                putInt("meanCn0", 38)
            }
        }
    }

    private fun syncOverlayState() {
        val shouldShow = isLoopActive &&
            store.isOverlayEnabled() &&
            MockPermissionHelper.canDrawOverlays(this)
        if (shouldShow) {
            attachOverlay()
        } else {
            detachOverlay()
        }
    }

    private fun attachOverlay() {
        mainHandler.post {
            if (overlayView != null || !isLoopActive) return@post
            val wm = windowManager ?: return@post
            val density = resources.displayMetrics.density
            val padH = (12 * density).toInt()
            val padV = (7 * density).toInt()

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, padV, padH, padV)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#D90F172A"))
                    cornerRadius = 20f * density
                    setStroke((1 * density).toInt(), Color.parseColor("#40FFFFFF"))
                }
            }

            val statusTv = TextView(this).apply {
                text = "📍 定位中 #$tickCounter"
                setTextColor(Color.WHITE)
                textSize = 12f
                paint.isFakeBoldText = true
            }
            overlayStatusTv = statusTv

            val stopTv = TextView(this).apply {
                text = "停止"
                setTextColor(Color.parseColor("#F87171"))
                textSize = 12f
                paint.isFakeBoldText = true
                setPadding((10 * density).toInt(), 0, 0, 0)
                setOnClickListener {
                    stop(this@MockLocationService)
                }
            }

            container.addView(statusTv)
            container.addView(stopTv)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (12 * density).toInt()
                y = (110 * density).toInt()
            }

            var initialX = 0
            var initialY = 0
            var downRawX = 0f
            var downRawY = 0f
            var moved = false

            container.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (abs(dx) > 8f || abs(dy) > 8f) {
                            moved = true
                            params.x = (initialX - dx).toInt()
                            params.y = (initialY + dy).toInt()
                            runCatching { wm.updateViewLayout(container, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            val openIntent = Intent(this@MockLocationService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            runCatching { startActivity(openIntent) }
                        }
                        true
                    }
                    else -> false
                }
            }

            runCatching {
                wm.addView(container, params)
                overlayView = container
                overlayParams = params
            }
        }
    }

    private fun updateOverlayTickText(tick: Long) {
        if (overlayView == null) return
        mainHandler.post {
            overlayStatusTv?.text = if (walkSpeedMps > 0f) {
                "🚶 行走中 #$tick"
            } else {
                "📍 定位中 #$tick"
            }
        }
    }

    private fun detachOverlay() {
        mainHandler.post {
            val view = overlayView ?: return@post
            runCatching { windowManager?.removeViewImmediate(view) }
            overlayView = null
            overlayParams = null
            overlayStatusTv = null
        }
    }

    private fun stopMocking(errorMessage: String?) {
        isLoopActive = false
        tickerHandler?.removeCallbacksAndMessages(null)
        detachOverlay()
        teardownTestProviders()
        releaseWakeLock()
        _state.value = _state.value.copy(
            isRunning = false,
            isWalking = false,
            walkSpeedMps = 0f,
            errorMessage = errorMessage
        )
        stopForegroundCompat()
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PUSH_INTERVAL_MS = 100L
        private const val FUSED_PROVIDER = "fused"

        const val ACTION_START_OR_UPDATE = "com.mocklocation.assistant.ACTION_START_OR_UPDATE"
        const val ACTION_STOP = "com.mocklocation.assistant.ACTION_STOP"
        const val ACTION_SET_WALK = "com.mocklocation.assistant.ACTION_SET_WALK"
        const val ACTION_REFRESH_OVERLAY = "com.mocklocation.assistant.ACTION_REFRESH_OVERLAY"

        const val EXTRA_WGS_LAT = "extra_wgs_lat"
        const val EXTRA_WGS_LNG = "extra_wgs_lng"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_JITTER = "extra_jitter"
        const val EXTRA_WALK_SPEED_MPS = "extra_walk_speed_mps"
        const val EXTRA_WALK_BEARING_DEG = "extra_walk_bearing_deg"

        private val _state = MutableStateFlow(MockState())
        val state: StateFlow<MockState> = _state.asStateFlow()

        fun startOrUpdate(
            context: Context,
            wgsLat: Double,
            wgsLng: Double,
            label: String,
            jitter: Boolean
        ) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_WGS_LAT, wgsLat)
                putExtra(EXTRA_WGS_LNG, wgsLng)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_JITTER, jitter)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateWalkMode(context: Context, speedMps: Float, bearingDeg: Float) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_SET_WALK
                putExtra(EXTRA_WALK_SPEED_MPS, speedMps)
                putExtra(EXTRA_WALK_BEARING_DEG, bearingDeg)
            }
            context.startService(intent)
        }

        fun refreshOverlay(context: Context) {
            if (!state.value.isRunning) return
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_REFRESH_OVERLAY
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
