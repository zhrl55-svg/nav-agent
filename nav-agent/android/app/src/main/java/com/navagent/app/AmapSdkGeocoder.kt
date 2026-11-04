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
import com.amap.api.services.routepoisearch.RoutePOISearch
import com.amap.api.services.routepoisearch.RoutePOISearchQuery
import java.util.concurrent.CopyOnWriteArrayList
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
    data class Poi(
        val name: String,
        val distance: Int,
        val latitude: Double,
        val longitude: Double,
        /** 详细地址（POI 搜索才有）。搜索栏列表用它做副标题 */
        val address: String = "",
        /**
         * POI 所在城市（如「石家庄市」）。
         *
         * ★ 用来判断「用户是不是真的要去外地」：
         *   解析结果城市跟当前城市不同不一定是错 —— 用户说「石家庄火车站」
         *   就是要去石家庄（1227 公里）。
         *   错的是用户说「水勘院超市」却匹配到石家庄的同名店。
         *   区别在于：**用户说的地名里有没有那个城市名**。
         */
        val city: String = "",
        /**
         * POI 所在区县（如「景泰县」「甘州区」）。
         *
         * ★ 距离兜底要用它 —— 用户可能只说县名不说市名
         *   （「景泰县条山路金星家园」里没有「白银」）。
         */
        val district: String = ""
    )

    /** 导航路线上的一个坐标点，按行驶方向排列。 */
    data class RoutePoint(
        val latitude: Double,
        val longitude: Double
    )

    /** 定位结果 */
    data class Fix(val latitude: Double, val longitude: Double, val city: String)

    // ------------------------------------------------------------------ 初始化

    private var inited = false

    /**
     * 最近一次创建的 PoiSearch —— **必须持有强引用**。
     *
     * ★ 为什么（实测踩过）：
     *   PoiSearch 是局部变量时，函数一返回它就没引用了，
     *   GC 可能在 SDK 的异步搜索任务真正执行前把它回收，
     *   表现是回调**迟迟不来**（候选搜索等了 8 秒超时才拿到结果）。
     *   搜索栏里同样的调用几百毫秒就来，是因为那时还没被 GC 回收。
     */
    private var lastSearch: PoiSearch? = null

    /** RoutePOISearch 同样要持有强引用，避免异步任务执行前被 GC 回收。 */
    private var lastRouteSearch: RoutePOISearch? = null

    /**
     * 沿途通用搜索会同时发多个周边请求。只用一个 lastSearch 字段会被后一个
     * 请求覆盖，这里集中持有并在回调后释放。
     */
    private val activeAroundSearches = CopyOnWriteArrayList<PoiSearch>()

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
                    // 新版 SDK（V11.3.100）把 setWifiActiveScan 移除了，
                    // isWifiActiveScan 变成只读属性 —— 赋值会编译报错。
                    // 不影响定位精度，只是少了「主动扫 WiFi」这个辅助手段。
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
     *
     * ★ 2026-11-04：改走 **Web API**（PlaceWebSearch.searchLocalFirst），不用 SDK 的 PoiSearch。
     *   原因实测过：SDK 的 `PoiSearch.Query(keyword, "", city)` + `setCityLimit(true)`
     *   **city 参数不生效** —— 传 city=张掖市 搜「水勘院超市」，
     *   返回的是**石家庄**的「槐中路135号水勘院(顺丰便利店)」，一路 1517 公里。
     *   Web API 的 citylimit 语义是对的，同样查询返回张掖甘州区的水勘院超市。
     *
     * ★ 搜索策略：**地名不带行政区划 → 优先本地**，本地搜不到再放开全国；
     *   带行政区划 → 直接全国搜。
     *
     * ★ Web API 是同步 HTTP，这里包一层到子线程，保持原接口的异步语义不变。
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
        Thread {
            val pois = runCatching {
                PlaceWebSearch.searchLocalFirst(keyword, city, limit = 5)
            }.getOrElse {
                Dbg.log("POI 搜索异常 keyword=$keyword：${it.message}")
                emptyList()
            }
            val point = pois.firstOrNull()?.let {
                GeoPoint(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    formatted = it.name,
                    // ★ 必须是**城市名**（如「石家庄市」），不是拼好的完整地址 ——
                    //   距离兜底要拿它跟用户说的地名比对（「石家庄火车站」里有没有「石家庄」）
                    city = it.city,
                    district = it.district
                )
            }
            if (point == null) {
                Dbg.log("POI 搜索无结果 keyword=$keyword")
            } else {
                Dbg.log(
                    "POI 命中「${point.formatted}」${point.latitude},${point.longitude}"
                )
            }
            onResult(point)
        }.apply { isDaemon = true; name = "poi-search" }.start()
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
            activeAroundSearches.add(search)
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
                    activeAroundSearches.remove(search)
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
                    activeAroundSearches.remove(search)
                }
            })
            search.searchPOIAsyn()
        }.onFailure {
            Dbg.log("高德周边搜索异常：${it.message}")
            onResult(emptyList())
        }
    }

    /**
     * 沿当前导航路线搜索官方支持的常见 POI 类型。
     *
     * 高德 `RoutePOISearch` 一次请求就能沿整条路线搜索，比在路线上取多个圆心
     * 反复做周边搜索更省流量，也不会漏掉两个采样圆之间的地点。
     * 它只支持固定类别；其他关键词由 [NearbyTool] 做采样兜底。
     *
     * @param route 当前导航剩余路线，必须按行驶方向排列
     * @param radius 路线两侧的搜索宽度，高德该接口最大支持 3000 米
     */
    fun searchAlongRoute(
        context: Context,
        route: List<RoutePoint>,
        keyword: String,
        radius: Int,
        limit: Int,
        onResult: (List<Poi>) -> Unit
    ) {
        init(context)
        val type = routePoiSearchType(keyword)
        if (type == null || route.size < 2) {
            onResult(emptyList())
            return
        }
        val safeRadius = radius.coerceIn(200, 3_000)
        val polyline = sampleRouteForQuery(route).map {
            LatLonPoint(it.latitude, it.longitude)
        }
        runCatching {
            val query = RoutePOISearchQuery(
                polyline,
                type,
                safeRadius
            )
            val search = RoutePOISearch(context.applicationContext, query)
            lastRouteSearch = search
            search.setPoiSearchListener(object : RoutePOISearch.OnRoutePOISearchListener {
                override fun onRoutePoiSearched(
                    result: com.amap.api.services.routepoisearch.RoutePOISearchResult?,
                    code: Int
                ) {
                    if (code != 1000) {
                        Dbg.log("高德沿途搜索失败 code=$code keyword=$keyword type=$type")
                        onResult(emptyList())
                        return
                    }
                    val pois = result?.routePois.orEmpty()
                        .mapNotNull { item ->
                            val point = item.point ?: return@mapNotNull null
                            Poi(
                                name = item.title ?: keyword,
                                distance = item.distance.toInt(),
                                latitude = point.latitude,
                                longitude = point.longitude
                            )
                        }
                        .sortedBy { it.distance }
                        .take(limit)
                    Dbg.log(
                        "高德沿途命中 ${pois.size} 个 keyword=$keyword type=$type radius=$safeRadius：" +
                            pois.joinToString { "${it.name}(${it.distance}m)" }
                    )
                    onResult(pois)
                }
            })
            search.searchRoutePOIAsyn()
        }.onFailure {
            Dbg.log("高德沿途搜索异常：${it.message}")
            onResult(emptyList())
        }
    }

    /** 当前关键词是否可由高德官方 RoutePOISearch 直接查询。 */
    fun supportsAlongRouteSearch(keyword: String): Boolean =
        routePoiSearchType(keyword) != null

    private fun routePoiSearchType(
        keyword: String
    ): RoutePOISearch.RoutePOISearchType? {
        val k = keyword.trim()
        if (k.isBlank()) return null
        fun has(vararg words: String) = words.any { k.contains(it) }
        return when {
            has("充电站", "充电桩", "充电") ->
                RoutePOISearch.RoutePOISearchType.TypeChargeStation
            has("加气站", "加气") ->
                RoutePOISearch.RoutePOISearchType.TypeFillingStation
            has("加油站", "加油", "油站") ->
                RoutePOISearch.RoutePOISearchType.TypeGasStation
            has("维修", "修车", "汽修", "保养") ->
                RoutePOISearch.RoutePOISearchType.TypeMaintenanceStation
            has("ATM", "atm", "取款", "取钱") ->
                RoutePOISearch.RoutePOISearchType.TypeATM
            has("厕所", "卫生间", "洗手间") ->
                RoutePOISearch.RoutePOISearchType.TypeToilet
            has("服务区") ->
                RoutePOISearch.RoutePOISearchType.TypeServiceArea
            has("餐厅", "饭店", "饭馆", "吃饭", "美食", "小吃", "咖啡") ->
                RoutePOISearch.RoutePOISearchType.TypeFood
            has("酒店", "宾馆", "旅馆", "住宿") ->
                RoutePOISearch.RoutePOISearchType.TypeHotel
            else -> null
        }
    }

    /** 路线很长时降采样，避免一次请求塞入过多坐标点。 */
    private fun sampleRouteForQuery(
        route: List<RoutePoint>,
        maxPoints: Int = 500
    ): List<RoutePoint> {
        if (route.size <= maxPoints) return route
        val last = route.lastIndex
        return List(maxPoints) { i ->
            route[(i.toLong() * last / (maxPoints - 1)).toInt()]
        }
    }

    // ------------------------------------------------------------------ 关键字搜索

    /**
     * 按关键字搜索 POI，给导航页顶部的地址搜索栏用。
     *
     * 和 [searchAroundList] 的区别：那个必须给圆心和半径（问"附近有什么"），
     * 这个只给城市做偏置、按相关度排序（问"XX 在哪"）。
     * 搜索结果不带圆心的话，用 `setCity` 限定在常驻城市，
     * 否则搜"西站"会返回全国几十个西站。
     *
     * @param city 城市名，可空 —— 空则不限定，按全国相关度排
     */
    fun searchKeyword(
        context: Context,
        keyword: String,
        city: String?,
        limit: Int = 8,
        onResult: (List<Poi>) -> Unit
    ) = searchKeywordInternal(context, keyword, city, limit, false, onResult)

    /**
     * 跨城市关键字搜索 —— **不限定城市**。
     *
     * ★ 为什么需要单独一个：
     *   `searchKeyword` 传了当前城市就等于给 POI 搜索加了 citylimit，
     *   搜「景泰县条山路金星家园」时人在张掖 → 只搜张掖市 → **0 条**
     *   （实测踩过：候选搜索返回 0，跨城地名根本搜不出来）。
     *
     *   记忆里那条「跨城市地名解析」的坑就是这个 —— 地理编码要动态开关 citylimit，
     *   POI 搜索也一样。
     *
     * ★ 排序：不限城市时按**距离**排（离用户近的优先），
     *   再叠加相关度。用户说「XX 那个地方」，近的那个多半是对的。
     */
    fun searchKeywordAnywhere(
        context: Context,
        keyword: String,
        limit: Int = 8,
        onResult: (List<Poi>) -> Unit
    ) = searchKeywordInternal(context, keyword, "", limit, true, onResult)

    private fun searchKeywordInternal(
        context: Context,
        keyword: String,
        city: String?,
        limit: Int,
        anywhere: Boolean,
        onResult: (List<Poi>) -> Unit
    ) {
        init(context)
        val query = PoiSearch.Query(keyword, "", city.orEmpty())
        query.pageSize = limit
        query.pageNum = 1
        // 有关键字时用相关度，不要距离排序 —— 用户找的是"那个地方"，不是"最近的"
        query.setDistanceSort(false)
        // ★ 跨城搜索必须显式关掉 citylimit。
        //   Query 里传了 city 字符串不代表只搜这座城 —— 还要 setCityLimit(false)。
        if (anywhere) {
            runCatching { query.setCityLimit(false) }
                .onFailure { Dbg.log("关闭 citylimit 失败：${it.message}") }
        }
        runCatching {
            val search = PoiSearch(context.applicationContext, query)
            // ★ ★ 必须持有强引用！
            //   PoiSearch 是个局部变量的话，函数一返回它就没引用了，
            //   GC 可能在 SDK 的异步任务执行前把它回收掉 ——
            //   表现是回调**迟迟不来**（实测第一次搜索等了 8 秒超时，
            //   而同样的调用放在搜索栏里几百毫秒就来，因为那时还没被 GC）。
            //   存进字段里保命。
            lastSearch = search
            search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResult?, code: Int) {
                    if (code != 1000) {
                        Dbg.log("高德关键字搜索失败 code=$code keyword=$keyword")
                        onResult(emptyList())
                        return
                    }
                    val pois = result?.pois.orEmpty().take(limit).map { item ->
                        Poi(
                            name = item.title ?: keyword,
                            distance = item.distance,
                            latitude = item.latLonPoint?.latitude ?: 0.0,
                            longitude = item.latLonPoint?.longitude ?: 0.0,
                            address = listOfNotNull(
                                item.provinceName,
                                item.cityName?.takeIf { it != item.provinceName },
                                item.adName,
                                item.snippet
                            ).filter { it.isNotBlank() }.distinct().joinToString("")
                        )
                    }
                    Dbg.log("高德关键字命中 ${pois.size} 个：${pois.joinToString { it.name }}")
                    try {
                        onResult(pois)
                    } catch (t: Throwable) {
                        Dbg.log("onResult 回调抛异常：${t.javaClass.simpleName}: ${t.message}")
                    }
                }

                override fun onPoiItemSearched(item: PoiItem?, code: Int) {}
            })
            search.searchPOIAsyn()
        }.onFailure {
            Dbg.log("高德关键字搜索异常：${it.message}")
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
