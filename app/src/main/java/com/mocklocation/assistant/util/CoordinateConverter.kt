package com.mocklocation.assistant.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

enum class CoordSystem {
    WGS84,
    GCJ02,
    BD09
}

/**
 * Coordinate system conversion utilities between WGS-84 (Android OS GPS),
 * GCJ-02 (Amap / Tencent / Google China), and BD-09 (Baidu Maps).
 */
object CoordinateConverter {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val X_PI = PI * 3000.0 / 180.0
    private const val EARTH_RADIUS_METERS = 6378137.0

    fun isOutOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    fun wgs84ToGcj02(lat: Double, lng: Double): LatLng {
        if (isOutOfChina(lat, lng)) return LatLng(lat, lng)
        val delta = delta(lat, lng)
        return LatLng(lat + delta.lat, lng + delta.lng)
    }

    fun gcj02ToWgs84(lat: Double, lng: Double): LatLng {
        if (isOutOfChina(lat, lng)) return LatLng(lat, lng)
        var wgsLat = lat
        var wgsLng = lng
        // 5-step fixed-point iteration reduces inverse error below 1e-7 degrees (< 1 cm)
        for (i in 0 until 5) {
            val gcj = wgs84ToGcj02(wgsLat, wgsLng)
            val dLat = gcj.lat - lat
            val dLng = gcj.lng - lng
            wgsLat -= dLat
            wgsLng -= dLng
            if (abs(dLat) < 1e-9 && abs(dLng) < 1e-9) break
        }
        return LatLng(wgsLat, wgsLng)
    }

    fun gcj02ToBd09(lat: Double, lng: Double): LatLng {
        val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = atan2(lat, lng) + 0.000003 * cos(lng * X_PI)
        val bdLng = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
        return LatLng(bdLat, bdLng)
    }

    fun bd09ToGcj02(lat: Double, lng: Double): LatLng {
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        val gcjLng = z * cos(theta)
        val gcjLat = z * sin(theta)
        return LatLng(gcjLat, gcjLng)
    }

    fun wgs84ToBd09(lat: Double, lng: Double): LatLng {
        val gcj = wgs84ToGcj02(lat, lng)
        return gcj02ToBd09(gcj.lat, gcj.lng)
    }

    fun bd09ToWgs84(lat: Double, lng: Double): LatLng {
        val gcj = bd09ToGcj02(lat, lng)
        return gcj02ToWgs84(gcj.lat, gcj.lng)
    }

    fun toWgs84(lat: Double, lng: Double, source: CoordSystem): LatLng {
        return when (source) {
            CoordSystem.WGS84 -> LatLng(lat, lng)
            CoordSystem.GCJ02 -> gcj02ToWgs84(lat, lng)
            CoordSystem.BD09 -> bd09ToWgs84(lat, lng)
        }
    }

    /**
     * Offsets a WGS-84 coordinate by northMeters and eastMeters.
     */
    fun offsetMeters(
        lat: Double,
        lng: Double,
        northMeters: Double,
        eastMeters: Double
    ): LatLng {
        val dLat = (northMeters / EARTH_RADIUS_METERS) * (180.0 / PI)
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val dLng = (eastMeters / (EARTH_RADIUS_METERS * cosLat)) * (180.0 / PI)
        val nextLat = (lat + dLat).coerceIn(-85.0, 85.0)
        var nextLng = lng + dLng
        if (nextLng > 180.0) nextLng -= 360.0
        if (nextLng < -180.0) nextLng += 360.0
        return LatLng(nextLat, nextLng)
    }

    /**
     * Parses user-pasted coordinate strings such as "39.9042, 116.4074" or "116.4074,39.9042".
     * Automatically swaps when the first number exceeds valid latitude range [-90, 90] and fits longitude [-180, 180],
     * because Amap / Baidu coordinate pickers output "lng,lat" order.
     */
    fun parseSmartCoordinateInput(raw: String): LatLng? {
        val cleaned = raw.trim()
            .replace("，", ",")
            .replace("；", ",")
            .replace(";", ",")
            .replace(Regex("\\s+"), ",")
            .trim(',')
        val parts = cleaned.split(",").filter { it.isNotBlank() }
        if (parts.size != 2) return null

        val first = parts[0].toDoubleOrNull() ?: return null
        val second = parts[1].toDoubleOrNull() ?: return null
        if (!first.isFinite() || !second.isFinite()) return null

        return when {
            abs(first) > 90.0 && abs(first) <= 180.0 && abs(second) <= 90.0 -> {
                LatLng(second, first)
            }
            abs(first) <= 90.0 && abs(second) <= 180.0 -> {
                LatLng(first, second)
            }
            else -> null
        }
    }

    private fun delta(lat: Double, lng: Double): LatLng {
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return LatLng(dLat, dLng)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
