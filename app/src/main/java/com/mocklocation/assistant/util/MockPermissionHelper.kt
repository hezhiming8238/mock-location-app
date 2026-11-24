package com.mocklocation.assistant.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mocklocation.assistant.service.MockLocationService

/**
 * Checks location permissions, verifies whether this app is selected as the
 * system mock location app in Developer Options, and opens system settings.
 */
object MockPermissionHelper {

    private const val PROBE_PROVIDER = "mock_probe_check"

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Safely probes whether the system permits this app to register a test location provider.
     * Returns true immediately when MockLocationService is already active so active providers
     * are never disrupted.
     */
    @Suppress("DEPRECATION")
    fun canUseMockLocation(context: Context): Boolean {
        if (MockLocationService.state.value.isRunning) {
            return true
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false

        return try {
            runCatching { lm.removeTestProvider(PROBE_PROVIDER) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val props = ProviderProperties.Builder()
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
                lm.addTestProvider(PROBE_PROVIDER, props)
            } else {
                lm.addTestProvider(
                    PROBE_PROVIDER,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
            }
            lm.setTestProviderEnabled(PROBE_PROVIDER, true)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        } finally {
            runCatching { lm.removeTestProvider(PROBE_PROVIDER) }
        }
    }

    fun openDeveloperSettings(context: Context) {
        val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(devIntent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
    }
}
