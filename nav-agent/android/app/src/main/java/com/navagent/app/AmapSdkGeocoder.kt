package com.navagent.app

import android.content.Context
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.AMapUtils
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.geocoder.GeocodeAddress
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 高德原生 SDK 版的地名解析：POI 搜索 + 地理编码 + 一次性定位。
 *
 * ## 为什么从 Web API 换到 SDK
 *
 * 之前用 `https://restapi.amap.com/v3/place/text` 等 Web 接口，有三个问题：
 *  1. **主线程不能发 HTTP** —— `NetworkOnMainThreadException`，所有调用都得自己开子线程；
 *  2. 多一个 Key 要管（AMAP_WEB_KEY 硬编码在 build.gradle.kts 里）；
 *  3. Web API 的 `citylimit` 语义和 SDK 的不完全一样，两套行为容易对不上。
 *
 * 换成 SDK 后：
 *  - 异步回调，天然不占主线程；
 *  - 只用 Android 平台的 Key（已经绑包名了），不需要 Web Key；
 *  - `PoiSearch.Query.setCityLimit(boolean)` 和 Web 的 `citylimit` 是同一个语义，可以精确对齐。
 *
 * ## 重复类问题已解决
 *
 * `com.amap.api:search` 和 `com.amap.api:navi-3dmap` 都带
 * `com/amap/apis/utils/core/api/{AMapUtilCoreApi,NetProxy}`，直接加依赖会报 Duplicate class。
 * 解法见 `core/build.gradle.kts` 的 `stripSearchDuplicates` 任务：把 search 那份剔掉，
 * 保留 navi-3dmap 的（search 那份是方法子集，navi 那份是超集）。
 *
 * ## 跨城市解析（重要）
 *
 * 高德 POI 搜索是**语义匹配**，限定城市时会把外地地名硬塞给本市的相似名字。
 * 实测（2026-11-01）：
 *   「景泰县条山路金星家园」city=张掖市 & citylimit=true  → 金星灯饰，张掖本地 6.8km  ← 错
 *   「景泰县条山路金星家园」city=张掖市 & citylimit=false → 金星家园，白银市景泰县 420km ← 对
 * 所以关键词里带行政区划时必须放开 citylimit。规则见 [REGION_HINT]。
 *
 * 这条规则是从 Web API 时代继承下来的，行为保持一致 —— 用户已经验证过它是对的。
 */
object AmapSdkGeocoder {

    private const val TAG = "AmapSdkGeocoder"

    /**
     * 关键词里出现这些字，说明用户点名了某个行政区（「景泰县XX」「白银市XX」），
     * 这时候**不能**限定在本市。
     *
     * 「区」不列入：「小区」「园区」「商圈」太常见，会误触发。
     */
    private val REGION_HINT = Regex("[省市县旗镇乡]")

    /** POI 坐标 */
    data class GeoPoint(
        val latitude: Double,
        val longitude: Double,
        /** POI 名称 */
        val formatted: String,
        /** POI 所在城市，如「白银市」 */
        val city: String = "",
        /** POI 所在区县，如「景泰县」 */
        val district: String = ""
    )

    /** 周边搜索结果。distance 单位米，-1 表示没给。 */
    data class Poi(val name: String, val distance: Int, val latitude: Double, val longitude: Double)

    /** 定位结果 */
    data class Fix(val latitude: Double, val longitude: Double, val city: String)

    // ------------------------------------------------------------------ 初始化

    private var inited = false

    /**
     * 高德 SDK 的隐私合规。**必须在任何 SDK 调用之前**做，否则定位和搜索都会失败。
     */
    @Synchronized
    fun init(context: Context) {
        if (inited) return
        inited = true
        val app = context.applicationContext
        runCatching {
            com.amap.api.maps.MapsInitializer.updatePrivacyShow(app, true, true)
            com.amap.api.maps.MapsInitializer.updatePrivacyAgree(app, true)
        }.onFailure { Dbg.log("高德隐私初始化失败：${it.message}") }
        runCatching {
            AMapLocationClient.updatePrivacyShow(app, true, true)
            AMapLocationClient.updatePrivacyAgree(app, true)
        }.onFailure { Dbg.log("高德定位隐私初始化失败：${it.message}") }
        runCatching {
            com.amap.api.services.core.ServiceSettings.updatePrivacyShow(app, true, true)
            com.amap.api.services.core.ServiceSettings.updatePrivacyAgree(app, true)
        }.onFailure { Dbg.log("高德搜索隐私初始化失败：${it.message}") }
        Dbg.log("高德 SDK 隐私初始化完成")
    }

    // ------------------------------------------------------------------ 定位

    /**
     * 一次性定位。取当前城市和坐标，用于周边搜索的中心点、城市偏置。
     *
     * 用的是 `navi-3dmap` 内置的 `AMapLocationClient`（高德位置 SDK 不单独引，
     * 引入会和 navi-3dmap 内置的那份撞车 —— 详见 README 踩坑记录）。
     *
     * 回调在主线程，调用方自己决定要不要再切线程。
     */
    fun locate(context: Context, timeoutMs: Long = 12_000, onResult: (Fix?) -> Unit) {
        init(context)
        val app = context.applicationContext
        var client: AMapLocationClient? = null
        val done = AtomicBoolean(false)

        // listener 要在 finish 里用到，声明必须在 finish 之前
        lateinit var listener: com.amap.api.location.AMapLocationListener

        fun finish(fix: Fix?) {
            if (!done.compareAndSet(false, true)) return
            runCatching {
                client?.stopLocation()
                client?.unRegisterLocationListener(listener)
                client?.onDestroy()
            }
            onResult(fix)
        }

        listener = object : com.amap.api.location.AMapLocationListener {
            override fun onLocationChanged(location: AMapLocation?) {
                if (location == null) {
                    Dbg.log("高德定位返回空")
                    finish(null)
                    return
                }
                if (location.errorCode != 0) {
                    Dbg.log("高德定位失败 code=${location.errorCode} info=${location.errorInfo}")
                    finish(null)
                    return
                }
                Dbg.log(
                    "高德定位成功 ${location.latitude},${location.longitude} " +
                        "city=${location.city} district=${location.district}"
                )
                finish(
                    Fix(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        city = location.city ?: ""
                    )
                )
            }
        }

        runCatching {
            client = AMapLocationClient(app)
            client!!.setLocationOption(
                AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation = true
                    setHttpTimeOut(timeoutMs)
                    isNeedAddress = true
                    isWifiActiveScan = true
                }
            )
            client!!.setLocationListener(listener)
            client!!.startLocation()
        }.onFailure {
            Dbg.log("高德定位启动失败：${it.message}")
            finish(null)
            return
        }

        // 兜底超时：SDK 偶尔不给回调，不能让调用方永远等着
        Thread {
            Thread.sleep(timeoutMs)
            if (done.compareAndSet(false, true)) {
                Dbg.log("高德定位超时")
                runCatching {
                    client?.stopLocation()
                    client?.unRegisterLocationListener(listener)
                    client?.onDestroy()
                }
                onResult(null)
            }
        }.apply { isDaemon = true; name = "amap-locate-timeout" }.start()
    }

    // ------------------------------------------------------------------ POI 搜索

    /**
     * POI 关键字搜索 —— 目的地解析的主力。
     *
     * 用户说的「高铁西站」「火车站」「加油站」是 POI 不是门牌地址，
     * 所以这里走 POI 搜索而不是地理编码。
     */
    fun searchPoi(
        context: Context,
        keyword: String,
        city: String? = null,
        onResult: (GeoPoint?) -> Unit
    ) {
        init(context)
        if (keyword.isBlank()) {
            onResult(null)
            return
        }
        // ★ 关键词自带行政区划时放开 citylimit（跨城市导航错乱的根因）
        val cityLimit = !city.isNullOrBlank() && !REGION_HINT.containsMatchIn(keyword)
        val query = PoiSearch.Query(keyword, "", city.takeIf { cityLimit })
        query.pageSize = 10
        query.pageNum = 1
        query.setCityLimit(cityLimit)

        runCatching {
            val search = PoiSearch(context.applicationContext, query)
            search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, code: Int) {
                    if (code != 1000) {
                        Dbg.log("高德 POI 搜索失败 code=$code keyword=$keyword")
                        onResult(null)
                        return
                    }
                    val item = result?.pois?.firstOrNull()
                    if (item == null) {
                        Dbg.log("高德 POI 搜索无结果 keyword=$keyword")
                        onResult(null)
                        return
                    }
                    Dbg.log(
                        "高德 POI 命中「${item.title}」${item.latLonPoint.latitude}," +
                            "${item.latLonPoint.longitude} city=${item.cityName}"
                    )
                    onResult(item.toGeoPoint())
                }

                override fun onPoiItemSearched(item: PoiItem?, code: Int) {
                    // 单条查询的回调，这里用不到
                }
            })
            search.searchPOIAsyn()
        }.onFailure {
            Dbg.log("高德 POI 搜索异常 keyword=$keyword：${it.message}")
            onResult(null)
        }
    }

    /**
     * 周边搜索，取最近 [limit] 个。
     *
     * 开车场景不该弹长列表，但「周围有什么好吃的」这种问法至少得给三个候选，
     * 只报一个会显得没搜到东西。
     */
    fun searchAroundList(
        context: Context,
        lat: Double,
        lon: Double,
        keyword: String,
        radius: Int = 3000,
        limit: Int = 3,
        onResult: (List<Poi>) -> Unit
    ) {
        init(context)
        val query = PoiSearch.Query(keyword, "", "")
        query.pageSize = maxOf(limit, 10)
        query.pageNum = 1
        query.setDistanceSort(true)

        runCatching {
            val search = PoiSearch(context.applicationContext, query)
            // radius=0 表示不限距离，但配合 setDistanceSort 就按距离排
            search.setBound(
                PoiSearch.SearchBound(
                    LatLonPoint(lat, lon),
                    if (radius > 0) radius else 50_000,
                    true
                )
            )
            search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, code: Int) {
                    if (code != 1000) {
                        Dbg.log("高德周边搜索失败 code=$code keyword=$keyword")
                        onResult(emptyList())
                        return
                    }
                    val pois = result?.pois?.take(limit).orEmpty().map { item ->
                        Poi(
                            name = item.title ?: keyword,
                            distance = item.distance,
                            latitude = item.latLonPoint.latitude,
                            longitude = item.latLonPoint.longitude
                        )
                    }
                    Dbg.log("高德周边命中 ${pois.size} 个：${pois.joinToString { "${it.name}(${it.distance}m)" }}")
                    onResult(pois)
                }

                override fun onPoiItemSearched(item: PoiItem?, code: Int) {
                }
            })
            search.searchPOIAsyn()
        }.onFailure {
            Dbg.log("高德周边搜索异常：${it.message}")
            onResult(emptyList())
        }
    }

    // ------------------------------------------------------------------ 地理编码

    /**
     * 地址 → 坐标。给明确门牌地址时用（POI 搜索搞不定「XX路XX号」这类）。
     */
    fun geocode(
        context: Context,
        address: String,
        city: String? = null,
        onResult: (GeoPoint?) -> Unit
    ) {
        init(context)
        if (address.isBlank()) {
            onResult(null)
            return
        }
        runCatching {
            val search = GeocodeSearch(context.applicationContext)
            search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(
                    geoCodeResult: com.amap.api.services.geocoder.RegeocodeResult?,
                    code: Int
                ) {
                    // 反向地理编码，这里用不到
                }

                override fun onGeocodeSearched(
                    geoCodeResult: com.amap.api.services.geocoder.GeocodeResult?,
                    code: Int
                ) {
                    if (code != 1000) {
                        Dbg.log("高德地理编码失败 code=$code address=$address")
                        onResult(null)
                        return
                    }
                    val addr = geoCodeResult?.geocodeAddressList?.firstOrNull()
                    if (addr == null) {
                        Dbg.log("高德地理编码无结果 address=$address")
                        onResult(null)
                        return
                    }
                    onResult(addr.toGeoPoint(address))
                }
            })
            val query = GeocodeQuery(address, city)
            search.getFromLocationNameAsyn(query)
        }.onFailure {
            Dbg.log("高德地理编码异常 address=$address：${it.message}")
            onResult(null)
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun PoiItem.toGeoPoint(): GeoPoint = GeoPoint(
        latitude = latLonPoint.latitude,
        longitude = latLonPoint.longitude,
        formatted = title ?: "",
        city = cityName ?: "",
        district = adName ?: ""
    )

    private fun GeocodeAddress.toGeoPoint(fallback: String): GeoPoint = GeoPoint(
        latitude = latLonPoint?.latitude ?: 0.0,
        longitude = latLonPoint?.longitude ?: 0.0,
        formatted = formatAddress ?: fallback,
        city = city ?: "",
        district = district ?: ""
    )

    /**
     * 两点直线距离（米）。
     *
     * SDK 返回的 `PoiItem.distance` 是路径距离，周边搜索有；
     * 但目的地解析这种没有 distance 字段的场景，自己算更省事。
     */
    fun lineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val meters = AMapUtils.calculateLineDistance(
            LatLng(lat1, lon1),
            LatLng(lat2, lon2)
        )
        return meters.toInt()
    }
}
