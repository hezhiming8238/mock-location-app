package com.mocklocation.assistant.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedPlace(
    val id: String,
    val name: String,
    val wgsLat: Double,
    val wgsLng: Double,
    val timestamp: Long
)

data class TargetLocation(
    val wgsLat: Double,
    val wgsLng: Double,
    val label: String
)

/**
 * Local persistence for target location, favorites, history, and simulation settings.
 */
class LocationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastTarget(): TargetLocation {
        val lat = prefs.getString(KEY_LAST_LAT, null)?.toDoubleOrNull() ?: DEFAULT_WGS_LAT
        val lng = prefs.getString(KEY_LAST_LNG, null)?.toDoubleOrNull() ?: DEFAULT_WGS_LNG
        val label = prefs.getString(KEY_LAST_LABEL, null) ?: DEFAULT_LABEL
        return TargetLocation(lat, lng, label)
    }

    fun saveLastTarget(wgsLat: Double, wgsLng: Double, label: String) {
        prefs.edit()
            .putString(KEY_LAST_LAT, wgsLat.toString())
            .putString(KEY_LAST_LNG, wgsLng.toString())
            .putString(KEY_LAST_LABEL, label)
            .apply()
    }

    fun isJitterEnabled(): Boolean = prefs.getBoolean(KEY_ENABLE_JITTER, true)

    fun setJitterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLE_JITTER, enabled).apply()
    }

    fun getAltitudeMeters(): Double =
        prefs.getString(KEY_ALTITUDE_METERS, null)?.toDoubleOrNull() ?: 45.0

    fun getStepMeters(): Double =
        prefs.getString(KEY_STEP_METERS, null)?.toDoubleOrNull() ?: 50.0

    fun setStepMeters(meters: Double) {
        prefs.edit().putString(KEY_STEP_METERS, meters.toString()).apply()
    }

    fun getFavorites(): List<SavedPlace> = readList(KEY_FAVORITES)

    fun isFavorite(wgsLat: Double, wgsLng: Double): Boolean {
        return getFavorites().any {
            kotlin.math.abs(it.wgsLat - wgsLat) < 1e-4 &&
                kotlin.math.abs(it.wgsLng - wgsLng) < 1e-4
        }
    }

    fun toggleFavorite(name: String, wgsLat: Double, wgsLng: Double): Boolean {
        val current = getFavorites().toMutableList()
        val existingIdx = current.indexOfFirst {
            kotlin.math.abs(it.wgsLat - wgsLat) < 1e-4 &&
                kotlin.math.abs(it.wgsLng - wgsLng) < 1e-4
        }
        return if (existingIdx >= 0) {
            current.removeAt(existingIdx)
            writeList(KEY_FAVORITES, current)
            false
        } else {
            current.add(
                0,
                SavedPlace(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    wgsLat = wgsLat,
                    wgsLng = wgsLng,
                    timestamp = System.currentTimeMillis()
                )
            )
            writeList(KEY_FAVORITES, current.take(50))
            true
        }
    }

    fun removeFavorite(id: String) {
        val updated = getFavorites().filterNot { it.id == id }
        writeList(KEY_FAVORITES, updated)
    }

    fun getHistory(): List<SavedPlace> = readList(KEY_HISTORY)

    fun addToHistory(name: String, wgsLat: Double, wgsLng: Double) {
        val current = getHistory().filterNot {
            kotlin.math.abs(it.wgsLat - wgsLat) < 1e-4 &&
                kotlin.math.abs(it.wgsLng - wgsLng) < 1e-4
        }.toMutableList()
        current.add(
            0,
            SavedPlace(
                id = UUID.randomUUID().toString(),
                name = name,
                wgsLat = wgsLat,
                wgsLng = wgsLng,
                timestamp = System.currentTimeMillis()
            )
        )
        writeList(KEY_HISTORY, current.take(MAX_HISTORY))
    }

    private fun readList(key: String): List<SavedPlace> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        SavedPlace(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            name = obj.optString("name", "未命名地点"),
                            wgsLat = obj.getDouble("wgsLat"),
                            wgsLng = obj.getDouble("wgsLng"),
                            timestamp = obj.optLong("timestamp", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeList(key: String, list: List<SavedPlace>) {
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("wgsLat", item.wgsLat)
                put("wgsLng", item.wgsLng)
                put("timestamp", item.timestamp)
            }
            array.put(obj)
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "mock_location_prefs"
        private const val KEY_LAST_LAT = "last_wgs_lat"
        private const val KEY_LAST_LNG = "last_wgs_lng"
        private const val KEY_LAST_LABEL = "last_label"
        private const val KEY_ENABLE_JITTER = "enable_jitter"
        private const val KEY_ALTITUDE_METERS = "altitude_meters"
        private const val KEY_STEP_METERS = "step_meters"
        private const val KEY_FAVORITES = "favorites_json"
        private const val KEY_HISTORY = "history_json"
        private const val MAX_HISTORY = 12

        // Beijing Tiananmen Square (WGS-84)
        const val DEFAULT_WGS_LAT = 39.907335
        const val DEFAULT_WGS_LNG = 116.391244
        const val DEFAULT_LABEL = "北京天安门"
    }
}
