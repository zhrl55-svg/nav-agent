package com.navagent.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.navagent.core.NavController
import com.navagent.core.VoiceGuide
import java.util.Locale

/**
 * 真实导航实现：高德 AMapNavi + AMapLocationClient。
 *
 * 设计要点：
 *  1. 关掉 SDK 自带 TTS（setUseInnerVoice(false)），改由 onGetNavigationText 把播报文本
 *     灌进我们自己的 VoiceGuide。这样"导航播报"和"用户对话"共用一条 TTS 队列，
 *     靠 Priority 抢占，不会两套声音打架。
 *  2. 起点用"经度,纬度"字符串传给 calculateDriveRoute 的 String 重载，
 *     由 SDK 内部处理，省掉 GeocodeSearch 依赖（见 core/build.gradle.kts 的注释）。
 *  3. 算路是异步的：navigateTo() 只是发起请求，真正 startNavi 在 onCalculateRouteSuccess 里。
 *  4. 导航界面用高德官方内置 UI（NavActivity 里配置），自绘 HUD 作为备选。
 *     重叠靠 AMapNaviViewOptions.setCrossLocation 解决 —— 告诉高德 HUD 只能画在哪个矩形里。
 */
class AmapNavController(
    private val context: Context,
    private val voice: VoiceGuide,
    private val onState: (String) -> Unit,
    private val onHud: (NavHud) -> Unit,
    /**
     * 首次拿到定位。
     *
     * 为什么要这个回调：只打开地图不导航时（首页点「导航」进来），
     * AMapNaviView 不会自动把镜头挪到当前位置，实测停在北京市 —— 
     * 必须自己 moveCamera 一次。
     */
    private val onFirstFix: (Double, Double) -> Unit = { _, _ -> }
) : NavController {

    private var navi: AMapNavi? = null
    private var locationClient: AMapLocationClient? = null

    @Volatile private var lastLat = 0.0
    @Volatile private var lastLon = 0.0
    @Volatile private var hasFix = false

    /**
     * 当前城市（如"张掖市"）。用来给地理编码/POI 搜索做偏置。
     *
     * 为什么必须有：口语地名大多是 POI 不是门牌地址。实测"高铁西站"不带城市
     * 被 geocode 解析到 529 公里外；带上城市后 place/text 能稳定命中本市那个站。
     */
    @Volatile var currentCity: String? = null
        private set

    @Volatile private var navigating = false
    @Volatile private var stepRemainMeters = 0
    @Volatile private var pathRemainSeconds = 0
    @Volatile private var pathRemainMeters = 0

    private var pendingDestination: String? = null

    /**
     * 上一次解析出来的地点（带城市/区县）。
     *
     * 为什么留着：播报时要把「白银市景泰县的金星家园」整句念出来。
     * 只念「金星家园」的话，用户听不出解析对没对 —— 实测就是在这儿栽的：
     * 说「景泰县条山路金星家园」被限定在张掖市内，命中了本地的「金星灯饰」，
     * 播报说「开始导航，全程6.8公里」，用户以为要去景泰，实际原地转圈。
     */
    @Volatile
    private var pendingPlace: AmapSdkGeocoder.GeoPoint? = null

    /** SDK 回调来自工作线程，UI/播报统一切回主线程 */
    private val main = Handler(Looper.getMainLooper())

    /** 必须在 AMapNaviView.onCreate() 之后调用 */
    fun start() {
        Dbg.log("nav.start()")
        runCatching {
            navi = AMapNavi.getInstance(context).also {
                it.addAMapNaviListener(listener)
                Dbg.log("AMapNavi.getInstance 成功 ver=" + AMapNavi.getVersion())
                // 关掉内置语音，否则会和我们自己的 TTS 双重播报
                it.setUseInnerVoice(false)

                // ★ 不要开 setMultipleRouteNaviMode(true)。
                //   官方语义是「多路径导航模式」：导航中 SDK 会实时比较所有候选路线的路况，
                //   **自动切换到更快的那条**。实测在市区短途会来回跳（候选路线长度还都一样），
                //   表现就是导航频繁改道、播报反复说「已为你换路」。
                //   2026-11-01 加过又撤了，别再开。

                // ★ 车辆信息：开启车行限制。详见 [applyCarInfo]。
                applyCarInfo(it)
            }
        }.onFailure {
            Log.e(TAG, "AMapNavi 初始化失败", it)
            Dbg.log("AMapNavi 初始化失败 " + it)
            onState("导航引擎初始化失败：${it.message}")
        }

        runCatching {
            // 注意：这些 setter 返回 AMapLocationClientOption（链式），不是 void，
            // 所以不能用 Kotlin 属性语法，只能显式调用。
            val option = AMapLocationClientOption().apply {
                setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy)
                setOnceLocation(false)
                setInterval(2000L)
                // 必须开：要 loc.city 做地理编码的城市偏置
                setNeedAddress(true)
            }
            locationClient = AMapLocationClient(context).apply {
                setLocationOption(option)
                setLocationListener { loc: AMapLocation? ->
                    if (loc != null && loc.errorCode == 0) {
                        lastLat = loc.latitude
                        lastLon = loc.longitude
                        loc.city?.takeIf { it.isNotBlank() }?.let { currentCity = it }
                        if (!hasFix) {
                            hasFix = true
                            Dbg.log("首次定位 $lastLat,$lastLon city=$currentCity")
                            Log.i(TAG, "首次定位成功 $lastLat,$lastLon")
                            onState("定位已就绪")
                            main.post { onFirstFix(lastLat, lastLon) }
                        }
                    } else if (loc != null) {
                        Dbg.log("定位失败 code=${loc.errorCode} ${loc.errorInfo}")
                        Log.w(TAG, "定位失败 code=${loc.errorCode} msg=${loc.errorInfo}")
                    }
                }
                startLocation()
            }
        }.onFailure {
            Log.e(TAG, "定位客户端初始化失败", it)
            onState("定位初始化失败：${it.message}")
        }
    }

    override fun navigateTo(destination: String) {
        Dbg.log("navigateTo($destination) hasFix=$hasFix city=$currentCity")
        val n = navi
        if (n == null) {
            onState("导航引擎未就绪")
            voice.speak("导航引擎还没准备好", VoiceGuide.Priority.USER)
            return
        }
        if (!hasFix) {
            onState("正在定位，请稍候再试")
            voice.speak("还没定位到当前位置，稍等一下再说", VoiceGuide.Priority.USER)
            return
        }
        pendingDestination = destination
        pendingPlace = null
        onState("正在解析「$destination」…")

        // ★ 城市偏置要等城市名解析出来。
        //   定位回调里坐标是先到的，`loc.city` 要靠 setNeedAddress(true) 反查地址，
        //   慢一拍（实测差几百毫秒）。hasFix 一变 true 就去搜索的话，
        //   currentCity 还是 null —— 城市偏置失效，跨城市地名会解析错
        //   （「景泰县条山路金星家园」可能命中本市的「金星灯饰」）。
        //   所以这里等一下城市，最长 3 秒。
        if (currentCity.isNullOrBlank()) {
            Dbg.log("坐标已就绪但城市未解析，等城市（最多 3 秒）")
            waitForCityThenResolve(destination)
            return
        }
        resolvePlace(destination)
    }

    /**
     * 等城市名解析出来后继续解析目的地。
     *
     * 城市来自定位的地址反查，通常几百毫秒内就有；超时就不等了，
     * 直接按无城市偏置解析（跟以前的行为一样，不会更糟）。
     */
    private fun waitForCityThenResolve(destination: String) {
        val deadline = System.currentTimeMillis() + CITY_WAIT_MS
        Thread {
            while (System.currentTimeMillis() < deadline) {
                if (!currentCity.isNullOrBlank()) {
                    Dbg.log("城市已就绪：$currentCity")
                    break
                }
                Thread.sleep(100)
            }
            if (currentCity.isNullOrBlank()) {
                Dbg.log("等城市超时，按无城市偏置解析")
            }
            resolvePlace(destination)
        }.apply { isDaemon = true; name = "wait-city" }.start()
    }

    /**
     * 解析目的地并算路。
     *
     * POI 搜索优先（口语地名是 POI 不是门牌地址），搜不到再退回地理编码。
     * 两步都是 SDK 异步回调，不会抛 NetworkOnMainThreadException。
     */
    private fun resolvePlace(destination: String) {
        val city = currentCity
        AmapSdkGeocoder.searchPoi(context, destination, city) { poi ->
            Dbg.log(
                "poi 搜索「$destination」city=$city → " +
                    (poi?.let { "${it.city}${it.district}/${it.formatted} ${it.latitude},${it.longitude}" } ?: "null")
            )
            if (poi != null) {
                onPlaceResolved(poi)
                return@searchPoi
            }
            // 兜底：地理编码
            AmapSdkGeocoder.geocode(context, destination, city) { geo ->
                Dbg.log(
                    "geocode 兜底「$destination」→ " +
                        (geo?.let { "${it.city}${it.district}/${it.formatted} ${it.latitude},${it.longitude}" } ?: "null")
                )
                if (geo == null) {
                    post {
                        onState("找不到「$destination」这个地方")
                        voice.speak("找不到$destination，换个说法试试", VoiceGuide.Priority.USER)
                    }
                } else {
                    onPlaceResolved(geo)
                }
            }
        }
    }

    /**
     * 拿到坐标后算路。
     *
     * 从 navigateTo 里拆出来是因为解析现在是异步回调，
     * POI 命中和 geocode 兜底都要汇到同一段算路逻辑。
     */
    private fun onPlaceResolved(point: AmapSdkGeocoder.GeoPoint) {
        val n = navi ?: return
        pendingPlace = point
        // ★ 算路**之前**再设一次车辆信息。
        //   官方文档说 setCarInfo 影响的是「路径规划时会躲避车辆限行区域和路线」，
        //   所以必须在 calculateDriveRoute 之前生效，不能只在 start() 里设一次。
        applyCarInfo(n)
        // 用无参构造 + setter，避开 NaviLatLng(double,double) 参数顺序的歧义
        val start = NaviLatLng().apply {
            latitude = lastLat
            longitude = lastLon
        }
        val end = NaviLatLng().apply {
            latitude = point.latitude
            longitude = point.longitude
        }
        // 调试起点：固定坐标重算，排除 GPS 漂移干扰（对比配置改动时用）
        debugStart?.let { (lat, lon) ->
            start.latitude = lat
            start.longitude = lon
            Dbg.log("★ 使用调试起点 $lat,$lon（忽略真实定位）")
        }
        Dbg.log("算路 ${start.latitude},${start.longitude} → ${end.latitude},${end.longitude}（${point.formatted}）")

        val strategy = PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT
        Dbg.log("算路策略 = $strategy (DRIVING_MULTIPLE_ROUTES_DEFAULT=10)")
        val ok = n.calculateDriveRoute(
            listOf(start),
            listOf(end),
            null,
            // ★ 用官方推荐的策略，不要用 DRIVING_DEFAULT。
            //
            //   官方文档（V11.3.100 API Doc · PathPlanningStrategy）原文：
            //     「DRIVING_DEFAULT —— **已过时**。推荐使用
            //       PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT」
            //     「DRIVING_MULTIPLE_ROUTES_DEFAULT —— 多路径-速度优先+躲避拥堵+距离较短，
            //       **建议使用该策略**，策略值 10」
            //
            //   ★ 2026-11-01 实测：换成带 AMapNaviCalRouteOptions 的重载、
            //     开 setIntelligentSorting(true)（智能排序），同一个起点算出来的
            //     路线一模一样。所以智能排序对「起点在小区内部」这个场景无效，
            //     没必要为了它改调用方式。详见 README 决策 7。
            strategy
        )
        Dbg.log("calculateDriveRoute 返回=$ok")
        post {
            onState(
                if (ok) "正在规划到 ${point.formatted} 的路线…" else "算路请求发送失败"
            )
            if (!ok) voice.speak("路线规划失败，换个说法试试", VoiceGuide.Priority.USER)
        }
    }

    private fun post(block: () -> Unit) = main.post(block)

    /**
     * 「白银市景泰县」+「金星家园」→「白银市景泰县的金星家园」。
     * 念全称是故意的：只念地名的话，用户分不清是本市同名地点还是外地那个。
     */
    private fun describePlace(p: AmapSdkGeocoder.GeoPoint): String {
        val region = buildString {
            if (p.city.isNotBlank()) append(p.city)
            // 直辖市的 city 已经等于 district，别重复念
            if (p.district.isNotBlank() && p.district != p.city) append(p.district)
        }
        if (region.isEmpty() || p.formatted.startsWith(region)) return p.formatted
        return "$region${p.formatted}"
    }

    /** 语音友好的距离：432.1公里 → 「432公里」，820米 → 「820米」 */
    private fun spokenDistance(meters: Int): String = when {
        meters >= 100_000 -> "${meters / 1000}公里"
        meters >= 1000 -> String.format(Locale.CHINA, "%.1f公里", meters / 1000.0)
        meters > 0 -> "${meters}米"
        else -> "距离不计"
    }

    /** 语音友好的时长：11700秒 → 「3小时15分钟」 */
    private fun spokenDuration(seconds: Int): String = when {
        seconds >= 3600 -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m == 0) "${h}小时" else "${h}小时${m}分钟"
        }
        seconds >= 60 -> "${seconds / 60}分钟"
        seconds > 0 -> "不到1分钟"
        else -> "时间不计"
    }

    /**
     * 设置车辆信息，开启**车行限制**。
     *
     * ★ 官方文档（V11.3.100 API Doc · AMapNavi.setCarInfo）原文：
     *   「设置车辆信息(包括车型，车牌，车高，车重等)，**路径规划时会躲避车辆限行区域和路线**」
     *   「目前小客车算路只考虑车牌因素，货车算路会考虑车高、车重等信息，
     *     其中 AMapCarInfo.setRestriction(boolean) 用于控制是否规避限行路段」
     *
     * ★ 为什么这是「南门不通车」那个问题的解法：
     *   不设车辆信息 = 算路不知道这条路过不去（限高杆/限宽路/货车禁行），
     *   直接给你规划过去。高德 App 会绕开，是因为它设了车辆信息。
     *
     * ★ 为什么要**每次算路前**都调：
     *   官方说这个信息影响的是「路径规划时」的行为，
     *   所以必须在 calculateDriveRoute 之前生效，不能只在初始化时设一次。
     *
     * 车牌：官方说小客车算路**只考虑车牌因素**（限行是按车牌尾号算的）。
     * 没填车牌时只能规避「结构性禁行」（限高/限宽/货车道），
     * 尾号限行那种要填车牌才管用 —— 设置项里留了入口（见 [Prefs]）。
     */
    private fun applyCarInfo(navi: AMapNavi) {
        runCatching {
            val carInfo = com.amap.api.navi.model.AMapCarInfo().apply {
                // 0 = 轿车（AMapCarInfo.setCarType 内部是 Integer.parseInt）
                setCarType("0")
                // 规避限行路段
                setRestriction(true)
                // 车牌（小客车限行按尾号算，填了才能规避尾号限行）
                Prefs.carNumber.takeIf { it.isNotBlank() }?.let { setCarNumber(it) }
            }
            navi.setCarInfo(carInfo)
            Dbg.log(
                "车辆信息已设：carType=${carInfo.carType} " +
                    "restriction=${carInfo.isRestriction} carNumber=${carInfo.carNumber}"
            )
        }.onFailure { e ->
            Dbg.log("设置车辆信息失败（算路可能不避限行路段）：${e.message}")
        }
    }

    override fun stop() {
        runCatching { navi?.stopNavi() }
        navigating = false
        pendingDestination = null
        stepRemainMeters = 0
        pathRemainSeconds = 0
        pathRemainMeters = 0
        onHud(NavHud(navigating = false))
    }

    /**
     * 页面切走时暂停导航（**不是停止**）。
     *
     * ★ 为什么必须区分 pause 和 stop：
     *   切到设置页或对话页只是临时离开，路线要留着，回来必须能继续。
     *   `stopNavi()` 会把路线和目的地都清掉 —— 用户反馈的
     *   「从聊天界面返回导航后前面的导航会消失」就是误用了 stop。
     *
     *   `pauseNavi()` 暂停语音播报和定位刷新，但路线还在，
     *   配合 `resumeNavi()` 回来就接着走。
     */
    fun pauseNavigation() {
        if (!navigating) return
        runCatching { navi?.pauseNavi() }
        Dbg.log("导航已暂停（路线保留）")
    }

    /** 从其他页面回来，恢复导航播报和定位刷新 */
    fun resumeNavigation() {
        if (!navigating) return
        runCatching { navi?.resumeNavi() }
        Dbg.log("导航已恢复")
    }

    override val isNavigating: Boolean
        get() = navigating

    override fun distanceToNextAction(): Int? = if (navigating) stepRemainMeters else null

    override fun remainingSeconds(): Int? = if (navigating) pathRemainSeconds else null

    /** 是否已拿到定位。算路前必须有起点，所以对外暴露给 UI 做等待判断。 */
    val hasLocationFix: Boolean
        get() = hasFix

    /**
     * 调试用：强制指定算路起点，忽略真实定位。
     *
     * 为什么要这个：室内 GPS 会在小区里漂几十米，而起点差 30m 就能让路线
     * 从南门变成北门。不固定起点，就没法判断「改配置到底有没有用」——
     * 实测踩过一次：以为智能排序生效了，其实是定位漂到了另一条路上。
     *
     * 入口：`am start ... --es slat 38.945004 --es slon 100.418646`
     */
    @Volatile
    var debugStart: Pair<Double, Double>? = null

    /** 当前坐标，给周边搜索用；没定位返回 null */
    fun currentLocation(): Pair<Double, Double>? =
        if (hasFix) lastLat to lastLon else null

    /**
     * 当前导航实际指向的地点全称（带城市/区县），如「白银市景泰县金星家园」。
     * 用户说「导航错了」时用它重报，好让用户判断是哪里解析歪了。
     */
    val currentPlaceName: String?
        get() = pendingPlace?.let { describePlace(it) } ?: pendingDestination

    /** 释放：Activity onDestroy 调用 */
    fun release() {
        runCatching {
            navi?.removeAMapNaviListener(listener)
            navi?.stopNavi()
        }
        runCatching { locationClient?.stopLocation(); locationClient?.onDestroy() }
        locationClient = null
        runCatching { AMapNavi.destroy() }
        navi = null
        navigating = false
    }

    private val listener = object : NavListenerAdapter() {

        override fun onInitNaviSuccess() {
            Log.i(TAG, "导航引擎就绪，版本 ${AMapNavi.getVersion()}")
        }

        override fun onInitNaviFailure() {
            onState("导航引擎初始化失败")
            voice.speak("导航引擎初始化失败", VoiceGuide.Priority.USER)
        }

        override fun onCalculateRouteSuccess(p0: AMapCalcRouteResult?) {
            val n = navi ?: return
            // 只记录候选数量，**不要**去 selectRouteId 遍历。
            // 遍历会把当前路线切到最后一条，而且多路径模式下 SDK 自己也会来回切，
            // 叠在一起就是导航频繁改道（实测故障）。
            Dbg.log("onCalculateRouteSuccess 候选=${p0?.getRouteid()?.size ?: 0}")

            val path = n.naviPath
            pathRemainSeconds = path?.allTime ?: 0
            pathRemainMeters = path?.allLength ?: 0

            // ★ 路线诊断：打出每段走哪条路。
            //   路线跟高德 App 不一致时，靠这个直接看是「走的路不同」还是「起终点不同」。
            //   （路名在 step 的 links 里 —— AMapNaviStep 自己没有 roadName 字段。）
            runCatching {
                path?.steps?.forEachIndexed { i, s ->
                    if (i < 8) {
                        val road = s.links?.mapNotNull { it.roadName }
                            ?.filter { it.isNotBlank() }
                            ?.distinct()
                            ?.joinToString(" → ")
                            ?: "（无名道路）"
                        Dbg.log("  段$i (${s.length}m): $road")
                    }
                }
            }.onFailure { Dbg.log("路径诊断失败：${it.message}") }

            // ★ 已经在导航时改道重算，startNavi 会返回 false（因为导航没停）。
            //   如果直接把 navigating 赋成返回值，就会把自己标成"没在导航"，
            //   然后 HUD 消失、问"还有多久"回答"现在没在导航"。实测踩过。
            val wasNavigating = navigating
            val started = n.startNavi(NaviType.GPS)
            navigating = started || wasNavigating
            Dbg.log("startNavi=$started pathLen=${path?.allLength} pathTime=${path?.allTime} wasNav=$wasNavigating")

            val place = pendingPlace
            val where = place?.let { describePlace(it) } ?: (pendingDestination ?: "目的地")
            // 目的地不在当前城市 —— 这种多半是跨市长途，必须让用户第一时间听出来对不对
            val farAway = place != null &&
                place.city.isNotBlank() &&
                !currentCity.isNullOrBlank() &&
                place.city != currentCity
            onState(if (navigating) "导航中 → $where" else "启动导航失败")
            if (navigating) {
                val stat = spokenDistance(pathRemainMeters) + "，" + spokenDuration(pathRemainSeconds)
                val text = when {
                    wasNavigating && farAway -> "已改道，目的地在外地：$where，全程$stat"
                    wasNavigating -> "已改道去$where，全程$stat"
                    farAway -> "注意，目的地不在${currentCity}：$where，全程$stat"
                    else -> "开始导航去$where，全程$stat"
                }
                Dbg.log("播报目的地=$where 跨市=$farAway")
                voice.speak(text, VoiceGuide.Priority.NAV)
                onHud(
                    NavHud(
                        navigating = true,
                        remainMeters = pathRemainMeters,
                        remainSeconds = pathRemainSeconds
                    )
                )
            }
        }

        override fun onCalculateRouteFailure(p0: AMapCalcRouteResult?) {
            val code = p0?.errorCode ?: -1
            val desc = p0?.errorDescription ?: "未知错误"
            Dbg.log("算路失败 code=$code desc=$desc")
            Log.w(TAG, "算路失败 code=$code desc=$desc")
            navigating = false
            onState("算路失败：$desc（$code）")
            voice.speak("路线规划失败，$desc", VoiceGuide.Priority.USER)
            onHud(NavHud(navigating = false))
        }

        override fun onNaviInfoUpdate(p0: NaviInfo?) {
            val info = p0 ?: return
            Dbg.log("NaviInfo step=${info.curStepRetainDistance} road=${info.nextRoadName} remain=${info.pathRetainDistance}/${info.pathRetainTime}")
            stepRemainMeters = info.curStepRetainDistance
            pathRemainSeconds = info.pathRetainTime
            pathRemainMeters = info.pathRetainDistance
            // 高德经常把下一条路给成"无名道路"（村道、园区路、新建路），
            // 直接显示很丑，退回当前路名更可读；都没有就给个兜底话术。
            val next = info.nextRoadName?.takeIf { it.isNotBlank() && it != UNNAMED_ROAD }
            val cur = info.currentRoadName?.takeIf { it.isNotBlank() && it != UNNAMED_ROAD }
            val road = next ?: cur ?: "沿当前道路"
            onHud(
                NavHud(
                    navigating = true,
                    iconType = info.iconType,
                    iconBitmap = info.iconBitmap,
                    stepMeters = stepRemainMeters,
                    nextRoad = road,
                    remainMeters = pathRemainMeters,
                    remainSeconds = pathRemainSeconds
                )
            )
        }

        /** SDK 生成的导航播报文本，接管到我们自己的 TTS 队列（NAV 优先级最高） */
        override fun onGetNavigationText(p0: String?) {
            val text = p0?.takeIf { it.isNotBlank() } ?: return
            Dbg.log("播报: $text")
            voice.speak(text, VoiceGuide.Priority.NAV)
        }

        override fun onArriveDestination() {
            navigating = false
            onState("已到达目的地")
            voice.speak("已到达目的地", VoiceGuide.Priority.NAV)
            onHud(NavHud(navigating = false))
        }

        override fun onReCalculateRouteForYaw() {
            voice.speak("路线已重新规划", VoiceGuide.Priority.NAV)
        }

        override fun onReCalculateRouteForTrafficJam() {
            voice.speak("前方拥堵，已为你换路", VoiceGuide.Priority.NAV)
        }
    }

    private companion object {
        const val TAG = "AmapNav"

        /** 高德对没有正式名称的道路统一返回这个字符串 */
        const val UNNAMED_ROAD = "无名道路"

        /**
         * 等城市名解析出来的最长时间（毫秒）。
         *
         * 定位回调里坐标先到，城市名要靠地址反查，慢一拍。
         * 不等的话城市偏置是 null，跨城市地名会解析错。
         */
        const val CITY_WAIT_MS = 3_000L
    }
}
