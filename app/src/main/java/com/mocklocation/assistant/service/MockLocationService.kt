package com.mocklocation.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.mocklocation.assistant.MainActivity
import com.mocklocation.assistant.R
import com.mocklocation.assistant.util.CoordinateConverter
import com.mocklocation.assistant.util.LocationStore
import com.mocklocation.assistant.util.MockPermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Foreground service that continuously injects mock GPS, Network, and Fused locations
 * into Android's LocationManager and Google Play Services FusedLocationProviderClient.
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mockJob: Job? = null

    private lateinit var locationManager: LocationManager
    private lateinit var store: LocationStore
    private var fusedClient: FusedLocationProviderClient? = null
    private val registeredProviders = mutableListOf<String>()

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

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        store = LocationStore(this)
        fusedClient = runCatching {
            LocationServices.getFusedLocationProviderClient(this)
        }.getOrNull()
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

            else -> {
                // Android 14+ throws SecurityException if startForeground(LOCATION) is called without location permission
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

                if (!setupTestProviders()) {
                    _state.value = _state.value.copy(
                        isRunning = false,
                        errorMessage = getString(R.string.err_mock_permission_required)
                    )
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }

                _state.value = MockState(
                    isRunning = true,
                    wgsLat = currentLat,
                    wgsLng = currentLng,
                    label = currentLabel,
                    errorMessage = null,
                    tickCount = _state.value.tickCount,
                    isWalking = walkSpeedMps > 0f,
                    walkSpeedMps = walkSpeedMps,
                    walkBearingDeg = walkBearingDeg
                )

                startMockLoop()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mockJob?.cancel()
        teardownTestProviders()
        serviceScope.cancel()
        if (_state.value.isRunning) {
            _state.value = _state.value.copy(isRunning = false, isWalking = false)
        }
        super.onDestroy()
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

    @Suppress("DEPRECATION")
    private fun setupTestProviders(): Boolean {
        teardownTestProviders()
        registeredProviders.clear()

        val gpsOk = registerProvider(
            name = LocationManager.GPS_PROVIDER,
            requiresNetwork = false,
            requiresSatellite = true,
            accuracyFine = true
        )
        if (!gpsOk) return false
        registeredProviders.add(LocationManager.GPS_PROVIDER)

        if (registerProvider(
                name = LocationManager.NETWORK_PROVIDER,
                requiresNetwork = true,
                requiresSatellite = false,
                accuracyFine = false
            )
        ) {
            registeredProviders.add(LocationManager.NETWORK_PROVIDER)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (registerProvider(
                    name = FUSED_PROVIDER,
                    requiresNetwork = true,
                    requiresSatellite = true,
                    accuracyFine = true
                )
            ) {
                registeredProviders.add(FUSED_PROVIDER)
            }
        }

        runCatching {
            fusedClient?.setMockMode(true)?.addOnFailureListener { }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun registerProvider(
        name: String,
        requiresNetwork: Boolean,
        requiresSatellite: Boolean,
        accuracyFine: Boolean
    ): Boolean {
        return try {
            runCatching { locationManager.removeTestProvider(name) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val props = ProviderProperties.Builder()
                    .setHasNetworkRequirement(requiresNetwork)
                    .setHasSatelliteRequirement(requiresSatellite)
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
                    false,
                    false,
                    true,
                    true,
                    true,
                    Criteria.POWER_LOW,
                    if (accuracyFine) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
                )
            }
            locationManager.setTestProviderEnabled(name, true)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun teardownTestProviders() {
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

    private fun startMockLoop() {
        mockJob?.cancel()
        mockJob = serviceScope.launch {
            var tick = _state.value.tickCount
            while (isActive) {
                try {
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
                        val jitterN = (Random.nextDouble() - 0.5) * 0.7
                        val jitterE = (Random.nextDouble() - 0.5) * 0.7
                        CoordinateConverter.offsetMeters(currentLat, currentLng, jitterN, jitterE)
                    } else {
                        CoordinateConverter.offsetMeters(currentLat, currentLng, 0.0, 0.0)
                    }

                    val gpsLocation = buildMockLocation(
                        provider = LocationManager.GPS_PROVIDER,
                        lat = pushTarget.lat,
                        lng = pushTarget.lng,
                        accuracy = 3.2f
                    )

                    for (provider in registeredProviders) {
                        val acc = if (provider == LocationManager.NETWORK_PROVIDER) 12.0f else 3.2f
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

                    tick++
                    _state.value = MockState(
                        isRunning = true,
                        wgsLat = currentLat,
                        wgsLng = currentLng,
                        label = currentLabel,
                        errorMessage = null,
                        tickCount = tick,
                        isWalking = walkSpeedMps > 0f,
                        walkSpeedMps = walkSpeedMps,
                        walkBearingDeg = walkBearingDeg
                    )

                    if (tick % 4L == 0L) {
                        updateNotification()
                    }
                } catch (_: SecurityException) {
                    stopMocking(getString(R.string.err_mock_permission_revoked))
                    return@launch
                } catch (_: IllegalArgumentException) {
                    if (!setupTestProviders()) {
                        stopMocking(getString(R.string.err_mock_permission_revoked))
                        return@launch
                    }
                } catch (_: Exception) {
                    // Keep loop resilient across transient ROM provider glitches
                }

                delay(PUSH_INTERVAL_MS)
            }
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
            speed = walkSpeedMps
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 3.0f
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = 5.0f
            }
            extras = Bundle().apply {
                putInt("satellites", 12)
            }
        }
    }

    private fun stopMocking(errorMessage: String?) {
        mockJob?.cancel()
        mockJob = null
        teardownTestProviders()
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
        private const val PUSH_INTERVAL_MS = 800L
        private const val FUSED_PROVIDER = "fused"

        const val ACTION_START_OR_UPDATE = "com.mocklocation.assistant.ACTION_START_OR_UPDATE"
        const val ACTION_STOP = "com.mocklocation.assistant.ACTION_STOP"
        const val ACTION_SET_WALK = "com.mocklocation.assistant.ACTION_SET_WALK"

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

        fun stop(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
