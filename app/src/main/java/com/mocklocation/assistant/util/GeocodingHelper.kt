package com.mocklocation.assistant.util

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class SearchPlace(
    val title: String,
    val subtitle: String,
    val wgsLat: Double,
    val wgsLng: Double
)

/**
 * Offline + online place search helper supporting smart coordinate parsing,
 * built-in popular cities, Android Geocoder, and Nominatim/Photon fallback.
 */
object GeocodingHelper {

    val BUILT_IN_CITIES: List<SearchPlace> = listOf(
        SearchPlace("北京 · 天安门", "北京市东城区", 39.907335, 116.391244),
        SearchPlace("上海 · 外滩", "上海市黄浦区中山东一路", 31.239663, 121.490555),
        SearchPlace("广州 · 广州塔", "广东省广州市海珠区", 23.109128, 113.318977),
        SearchPlace("深圳 · 市民中心", "广东省深圳市福田区", 22.548456, 114.054542),
        SearchPlace("杭州 · 西湖断桥", "浙江省杭州市西湖区", 30.259150, 120.145683),
        SearchPlace("成都 · 天府广场", "四川省成都市青羊区", 30.659462, 104.065735),
        SearchPlace("重庆 · 解放碑", "重庆市渝中区", 29.558270, 106.577232),
        SearchPlace("武汉 · 黄鹤楼", "湖北省武汉市武昌区", 30.546950, 114.297710),
        SearchPlace("西安 · 钟楼", "陕西省西安市碑林区", 34.261013, 108.942282),
        SearchPlace("南京 · 新街口", "江苏省南京市秦淮区", 32.043934, 118.778702),
        SearchPlace("苏州 · 东方之门", "江苏省苏州市工业园区", 31.318610, 120.674410),
        SearchPlace("天津 · 天津之眼", "天津市河北区", 39.153860, 117.181620),
        SearchPlace("长沙 · 橘子洲头", "湖南省长沙市岳麓区", 28.188730, 112.957120),
        SearchPlace("郑州 · 二七纪念塔", "河南省郑州市二七区", 34.752460, 113.663460),
        SearchPlace("厦门 · 鼓浪屿", "福建省厦门市思明区", 24.447920, 118.063130),
        SearchPlace("青岛 · 五四广场", "山东省青岛市市南区", 36.061630, 120.381260),
        SearchPlace("三亚 · 三亚湾", "海南省三亚市天涯区", 18.252847, 109.511909),
        SearchPlace("拉萨 · 布达拉宫", "西藏自治区拉萨市城关区", 29.655490, 91.118590),
        SearchPlace("香港 · 维多利亚港", "香港特别行政区", 22.288900, 114.173500),
        SearchPlace("台北 · 台北101", "台湾省台北市信义区", 25.033964, 121.564468),
        SearchPlace("东京 · 东京塔", "日本东京都港区", 35.658581, 139.745433),
        SearchPlace("新加坡 · 滨海湾", "新加坡 Marina Bay", 1.283400, 103.860700),
        SearchPlace("伦敦 · 大本钟", "英国伦敦 Westminster", 51.500729, -0.124625),
        SearchPlace("纽约 · 时代广场", "美国纽约 Manhattan", 40.758896, -73.985130),
        SearchPlace("悉尼 · 悉尼歌剧院", "澳大利亚悉尼", -33.856784, 151.215297)
    )

    suspend fun search(context: Context, query: String): List<SearchPlace> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext BUILT_IN_CITIES

            val results = mutableListOf<SearchPlace>()

            // 1. Direct coordinate string match (offers WGS-84 & GCJ-02 options when in China)
            CoordinateConverter.parseSmartCoordinateInput(trimmed)?.let { parsed ->
                results.add(
                    SearchPlace(
                        title = "按 WGS-84 坐标定位",
                        subtitle = String.format(Locale.US, "%.6f, %.6f", parsed.lat, parsed.lng),
                        wgsLat = parsed.lat,
                        wgsLng = parsed.lng
                    )
                )
                if (!CoordinateConverter.isOutOfChina(parsed.lat, parsed.lng)) {
                    val converted = CoordinateConverter.gcj02ToWgs84(parsed.lat, parsed.lng)
                    results.add(
                        SearchPlace(
                            title = "按高德/腾讯坐标 (GCJ-02) 纠偏定位",
                            subtitle = String.format(
                                Locale.US,
                                "输入 %.6f, %.6f → WGS84 %.6f, %.6f",
                                parsed.lat, parsed.lng, converted.lat, converted.lng
                            ),
                            wgsLat = converted.lat,
                            wgsLng = converted.lng
                        )
                    )
                }
                return@withContext results
            }

            // 2. Instant built-in city/landmark dictionary match
            val lower = trimmed.lowercase(Locale.getDefault())
            results.addAll(
                BUILT_IN_CITIES.filter {
                    it.title.lowercase(Locale.getDefault()).contains(lower) ||
                        it.subtitle.lowercase(Locale.getDefault()).contains(lower)
                }
            )

            // 3. System Android Geocoder
            if (Geocoder.isPresent()) {
                runCatching {
                    val geocoder = Geocoder(context, Locale.CHINA)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(trimmed, 6)
                    addresses?.forEach { addr ->
                        val title = addr.featureName ?: addr.locality ?: trimmed
                        val sub = (0..addr.maxAddressLineIndex)
                            .mapNotNull { idx -> addr.getAddressLine(idx) }
                            .joinToString(" ")
                            .ifBlank { listOfNotNull(addr.adminArea, addr.locality).joinToString(" ") }
                        results.add(
                            SearchPlace(
                                title = title,
                                subtitle = sub.ifBlank { "系统地址解析结果" },
                                wgsLat = addr.latitude,
                                wgsLng = addr.longitude
                            )
                        )
                    }
                }
            }

            // 4. Online Nominatim fallback when few results were found
            if (results.size < 3) {
                results.addAll(queryNominatim(trimmed))
            }

            deduplicate(results)
        }

    private fun queryNominatim(query: String): List<SearchPlace> {
        val list = mutableListOf<SearchPlace>()
        var conn: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL(
                "https://nominatim.openstreetmap.org/search?format=jsonv2&accept-language=zh-CN&limit=6&q=$encoded"
            )
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MockLocationAssistant/1.0")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj: JSONObject = arr.getJSONObject(i)
                    val displayName = obj.optString("display_name", query)
                    val parts = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val title = parts.firstOrNull() ?: query
                    val subtitle = parts.drop(1).take(3).joinToString(" · ").ifBlank { displayName }
                    val lat = obj.optString("lat").toDoubleOrNull()
                    val lng = obj.optString("lon").toDoubleOrNull()
                    if (lat != null && lng != null) {
                        list.add(SearchPlace(title, subtitle, lat, lng))
                    }
                }
            }
        } catch (_: Exception) {
            // Network unavailable or blocked; silently keep offline/Geocoder results
        } finally {
            conn?.disconnect()
        }
        return list
    }

    private fun deduplicate(places: List<SearchPlace>): List<SearchPlace> {
        val out = mutableListOf<SearchPlace>()
        for (candidate in places) {
            val duplicate = out.any { existing ->
                kotlin.math.abs(existing.wgsLat - candidate.wgsLat) < 0.002 &&
                    kotlin.math.abs(existing.wgsLng - candidate.wgsLng) < 0.002
            }
            if (!duplicate) out.add(candidate)
        }
        return out.take(12)
    }
}
