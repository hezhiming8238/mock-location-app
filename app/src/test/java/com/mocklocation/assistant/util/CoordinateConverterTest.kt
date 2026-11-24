package com.mocklocation.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoordinateConverterTest {

    @Test
    fun wgs84AndGcj02RoundTripBeijing() {
        val wgsLat = 39.908823
        val wgsLng = 116.397470
        val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLng)
        // GCJ-02 offset in Beijing should be noticeable (~hundreds of meters)
        assertTrue(abs(gcj.lat - wgsLat) > 0.0005)
        assertTrue(abs(gcj.lng - wgsLng) > 0.001)

        val back = CoordinateConverter.gcj02ToWgs84(gcj.lat, gcj.lng)
        assertEquals(wgsLat, back.lat, 1e-6)
        assertEquals(wgsLng, back.lng, 1e-6)
    }

    @Test
    fun wgs84AndGcj02RoundTripShanghaiAndShenzhen() {
        val samples = listOf(
            LatLng(31.230416, 121.473701), // Shanghai
            LatLng(22.543096, 114.057865)  // Shenzhen
        )
        for (sample in samples) {
            val gcj = CoordinateConverter.wgs84ToGcj02(sample.lat, sample.lng)
            val back = CoordinateConverter.gcj02ToWgs84(gcj.lat, gcj.lng)
            assertEquals(sample.lat, back.lat, 1e-6)
            assertEquals(sample.lng, back.lng, 1e-6)
        }
    }

    @Test
    fun bd09RoundTripAccuracy() {
        val wgsLat = 30.274085
        val wgsLng = 120.155070
        val bd = CoordinateConverter.wgs84ToBd09(wgsLat, wgsLng)
        val back = CoordinateConverter.bd09ToWgs84(bd.lat, bd.lng)
        assertEquals(wgsLat, back.lat, 1e-5)
        assertEquals(wgsLng, back.lng, 1e-5)
    }

    @Test
    fun overseasCoordinatesStayUnchanged() {
        val tokyoLat = 35.658581
        val tokyoLng = 139.745433
        val gcj = CoordinateConverter.wgs84ToGcj02(tokyoLat, tokyoLng)
        assertEquals(tokyoLat, gcj.lat, 1e-9)
        assertEquals(tokyoLng, gcj.lng, 1e-9)
    }

    @Test
    fun smartCoordinateParserHandlesLatLngAndLngLat() {
        val latLng = CoordinateConverter.parseSmartCoordinateInput("39.9042, 116.4074")
        assertNotNull(latLng)
        assertEquals(39.9042, latLng!!.lat, 1e-6)
        assertEquals(116.4074, latLng.lng, 1e-6)

        val swapped = CoordinateConverter.parseSmartCoordinateInput("116.4074，39.9042")
        assertNotNull(swapped)
        assertEquals(39.9042, swapped!!.lat, 1e-6)
        assertEquals(116.4074, swapped.lng, 1e-6)

        assertNull(CoordinateConverter.parseSmartCoordinateInput("北京天安门"))
        assertNull(CoordinateConverter.parseSmartCoordinateInput("200.0, 120.0"))
    }
}
