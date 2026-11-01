package com.navagent.app

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 高德 Web 服务 API 的最小封装，只做一件事：地名 → 经纬度。
 *
 * 为什么不用 SDK 里的 GeocodeSearch：
 * 它在 com.amap.api:search 里，而 search 与 navi-3dmap 有无法排除的重复类
 * （com.amap.apis.utils.core.api.AMapUtilCoreApi），导航必须保留 navi-3dmap，
 * 所以只能把地理编码挪到 Web API。
 *
 * 注意坐标系：高德 Web API 返回的是 GCJ-02（火星坐标），与 NaviLatLng 一致，不需要转换。
 */
object AmapWebGeocoder {

    private const val TAG = "AmapGeocoder"
    private const val ENDPOINT = "https://restapi.amap.com/v3/geocode/geo"
    private const val POI_ENDPOINT = "https://restapi.amap.com/v3/place/text"
    private const val AROUND_ENDPOINT = "https://restapi.amap.com/v3/place/around"

    /**
     * 关键词里出现这些字，说明用户点名了某个行政区（「景泰县XX」「白银市XX」），
     * 这时候**不能**限定在本市。
     *
     * 实测（2026-11-01）：「景泰县条山路金星家园」
     *   city=张掖市 & citylimit=true  → 金星灯饰(东部建材市场店)，张掖本地，6.8km   ← 错
     *   city=张掖市 & citylimit=false → 金星家园，白银市景泰县，432km              ← 对
     * 高德的 place/text 是语义匹配，限定城市时会把外地地名硬塞给本市的相似名字。
     *
     * 「区」不列入：「小区」「园区」「商圈」太常见，会误触发。
     */
    private val REGION_HINT = Regex("[省市县旗镇乡]")

    data class GeoPoint(
        val latitude: Double,
        val longitude: Double,
        val formatted: String,
        /** POI 所在城市，如「白银市」。地理编码接口可能给空 */
        val city: String = "",
        /** POI 所在区县，如「景泰县」 */
        val district: String = ""
    )

    /** 周边搜索结果。distance 单位米，-1 表示没给。 */
    data class Poi(val name: String, val distance: Int, val latitude: Double, val longitude: Double)

    /**
     * 周边搜索，取最近 [limit] 个。
     *
     * 开车场景不该弹长列表，但「周围有什么好吃的」这种问法至少得给三个候选，
     * 只报一个会显得没搜到东西。
     */
    fun searchAroundList(
        lat: Double,
        lon: Double,
        keyword: String,
        radius: Int = 3000,
        limit: Int = 3
    ): List<Poi> {
        val url = buildString {
            append(AROUND_ENDPOINT)
            append("?key=").append(BuildConfig.AMAP_WEB_KEY)
            append("&location=").append(String.format(Locale.US, "%.6f,%.6f", lon, lat))
            append("&keywords=").append(URLEncoder.encode(keyword, "UTF-8"))
            append("&radius=").append(radius)
            append("&offset=").append(limit)
            append("&page=1&extensions=base&sortrule=distance")
        }
        return request(url) { json ->
            val pois = json.optJSONArray("pois") ?: return@request emptyList()
            (0 until minOf(pois.length(), limit)).mapNotNull { i ->
                val p = pois.optJSONObject(i) ?: return@mapNotNull null
                val loc = parseLocation(p.optString("location")) ?: return@mapNotNull null
                Poi(
                    name = p.optString("name", keyword),
                    distance = p.optString("distance").toIntOrNull() ?: -1,
                    latitude = loc.latitude,
                    longitude = loc.longitude
                )
            }
        } ?: emptyList()
    }

    /**
     * 周边搜索，只取最近的一个。
     *
     * 单结果场景（比如模型只想要一个点）用这个；
     * 用户问「附近有什么好吃的」走 [searchAroundList] 拿候选列表。
     */
    fun searchAround(
        lat: Double,
        lon: Double,
        keyword: String,
        radius: Int = 3000
    ): Poi? = searchAroundList(lat, lon, keyword, radius, 1).firstOrNull()

    /**
     * POI 关键字搜索。**比地理编码更适合口语地名**：
     * 用户说的"高铁西站""火车站""加油站"是 POI 不是门牌地址，
     * geocode 接口会按字符串硬匹配，实测"高铁西站"被解析到了 529 公里外；
     * place/text 带 city 偏置后能稳定命中本市那个站。
     *
     * 同步调用，必须在子线程执行。
     */
    fun searchPoi(keyword: String, city: String? = null): GeoPoint? {
        // ★ 关键词自带行政区划时放开 citylimit（见 REGION_HINT 注释，这是跨城市解析的关键）
        val limit = !city.isNullOrBlank() && !REGION_HINT.containsMatchIn(keyword)
        val url = buildString {
            append(POI_ENDPOINT)
            append("?key=").append(BuildConfig.AMAP_WEB_KEY)
            append("&keywords=").append(URLEncoder.encode(keyword, "UTF-8"))
            append("&offset=1&page=1&extensions=base")
            if (!city.isNullOrBlank()) append("&city=").append(URLEncoder.encode(city, "UTF-8"))
            append("&citylimit=").append(limit)
        }
        return request(url) { json ->
            val pois = json.optJSONArray("pois") ?: return@request null
            if (pois.length() == 0) return@request null
            val p = pois.getJSONObject(0)
            parseLocation(p.optString("location"))?.let { loc ->
                GeoPoint(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    formatted = p.optString("name", keyword),
                    city = p.optString("cityname"),
                    district = p.optString("adname")
                )
            }
        }
    }

    /** 同步调用，必须在子线程执行。失败返回 null。 */
    fun geocode(address: String, city: String? = null): GeoPoint? {
        // 地理编码同样：给了行政区划就别限定本市
        val limit = !city.isNullOrBlank() && !REGION_HINT.containsMatchIn(address)
        val url = buildString {
            append(ENDPOINT)
            append("?key=").append(BuildConfig.AMAP_WEB_KEY)
            append("&address=").append(URLEncoder.encode(address, "UTF-8"))
            if (!city.isNullOrBlank()) {
                append("&city=").append(URLEncoder.encode(city, "UTF-8"))
                append("&citylimit=").append(limit)
            }
        }
        return request(url) { json ->
            val geocodes = json.optJSONArray("geocodes") ?: return@request null
            if (geocodes.length() == 0) return@request null
            val first = geocodes.getJSONObject(0)
            parseLocation(first.optString("location"))?.let { loc ->
                GeoPoint(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    formatted = first.optString("formatted_address", address),
                    city = first.optString("city"),
                    district = first.optString("district")
                )
            }
        }
    }

    private fun parseLocation(loc: String): GeoPoint? {
        val parts = loc.split(",")
        if (parts.size != 2) return null
        return runCatching {
            GeoPoint(parts[1].trim().toDouble(), parts[0].trim().toDouble(), "")
        }.getOrNull()
    }

    private fun <T> request(url: String, parse: (JSONObject) -> T?): T? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Dbg.log("AmapWebGeocoder request url=$url")
            val json = JSONObject(body)

            if (json.optString("status") != "1") {
                Log.w(TAG, "请求失败 info=${json.optString("info")} code=${json.optString("infocode")}")
                return null
            }
            parse(json)
        } catch (e: IOException) {
            Log.e(TAG, "网络异常", e)
            Dbg.log("AmapWebGeocoder 网络异常 ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "解析异常", e)
            Dbg.log("AmapWebGeocoder 解析异常 ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
