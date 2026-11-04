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
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.navagent.core.NavController
import com.navagent.core.VoiceGuide
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次算路请求的真实结果。
 *
 * 只有 STARTED / REROUTED 能对用户说「已开始导航」。FAILED / TIMEOUT 必须如实返回，
 * 不能再用「正在导航」掩盖引擎未就绪、地点不存在或算路失败。
 */
data class NavigationAttempt(
    val status: Status,
    val routeInfo: String = "",
    val message: String = ""
) {
    enum class Status { STARTED, REROUTED, FAILED, TIMEOUT }

    val succeeded: Boolean
        get() = status == Status.STARTED || status == Status.REROUTED
}

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
    private val onFirstFix: (Double, Double) -> Unit = { _, _ -> },
    /**
     * 算路完成、导航开始（2026-11-04）。
     *
     * ★ 用来做「先看全程，5 秒后回到当前位置」——
     *   算路成功了但用户还不知道路线长什么样、往哪走，
     *   直接切到跟随视角会让人一头雾水。全程图看一眼，路线就在心里了。
     *
     * @param meters 全程长度（米）
     * @param seconds 全程时间（秒）
     * @param isReroute 是不是导航中改道（改道时不打断当前视角）
     */
    private val onRouteReady: (meters: Int, seconds: Int, isReroute: Boolean) -> Unit = { _, _, _ -> }
) : NavController {

    private var navi: AMapNavi? = null
    private var locationClient: AMapLocationClient? = null

    @Volatile private var lastLat = 0.0
    @Volatile private var lastLon = 0.0
    @Volatile private var hasFix = false

    /**
     * 高德导航车标的实时位置。
     *
     * 定位客户端的坐标不一定跟导航车标同步：模拟导航时它停在出发点，部分车机
     * 也会让两者有几秒延迟。沿途搜索必须用这个坐标，否则会把已经走过的路段
     * 重新算进「前方」。
     */
    @Volatile private var navLat = 0.0
    @Volatile private var navLon = 0.0
    @Volatile private var hasNavCoord = false

    /**
     * 当前城市（如"张掖市"）。用来给地理编码/POI 搜索做偏置。
     *
     * 为什么必须有：口语地名大多是 POI 不是门牌地址。实测"高铁西站"不带城市
     * 被 geocode 解析到 529 公里外；带上城市后 place/text 能稳定命中本市那个站。
     */
    @Volatile var currentCity: String? = null
        private set

    @Volatile private var navigating = false

    /**
     * 用户请求了停止导航（SDK 的 stopNavi 是软停止，会有延迟）。
     *
     * ★ 用途（2026-11-04）：
     *   stopNavi() 之后 SDK 还会再画一帧，甚至触发一次重新算路，
     *   导致「退出导航后又播一遍准备出发」（用户反馈）。
     *   这个标志用来识别「这是停止后的残留回调」，忽略掉。
     *
     *   navigateTo() 重新发起导航时会清掉。
     */
    @Volatile private var stopRequested = false

    /**
     * 「路线算好了」的信号（2026-11-04）。
     *
     * ★ 为什么需要：
     *   算路是异步的 —— navigateTo() 只是发起请求，
     *   真正 startNavi 在 onCalculateRouteSuccess 里（几百毫秒到几秒）。
     *   工具如果不等就返回，模型拿到的就是「正在算路」这种中间态，
     *   会照着念出来 —— 用户听到「正在规划张掖西站」，
     *   但地图其实已经开始导航了，两边不同步（用户反馈）。
     *
     *   工具侧 await 这个 latch，拿到的就是**真实**结果：
     *   「已导航到张掖西站，全程 2.6 公里，约 9 分钟」。
     */
    private class RouteWait {
        val latch = CountDownLatch(1)
        val result = AtomicReference<NavigationAttempt?>()
    }

    @Volatile
    private var routeWait: RouteWait? = null

    /** 超时取消后，忽略 SDK 稍晚才到的算路成功回调。 */
    @Volatile
    private var cancelPendingRoute = false

    @Volatile private var stepRemainMeters = 0
    @Volatile private var pathRemainSeconds = 0
    @Volatile private var pathRemainMeters = 0

    /**
     * 前方剩余红绿灯个数（SDK `NaviInfo.getRouteRemainLightCount`）。
     *
     * ★ 2026-11-03：用户反馈「没有显示红绿灯时间」。
     *   SDK 的倒计时只对有信号数据的路口下发，本地拿不到通用倒计时；
     *   但「还剩几个灯」是全量数据，稳定可用 —— 先把它显示出来，
     *   比什么都不显示有用。真正的秒数交给 SDK 内置的红绿灯视图。
     */
    @Volatile var remainLights: Int = 0
        private set

    /** 最近一次定位成功的时刻，用来判断信号是否断流 */
    @Volatile private var lastFixMs = 0L

    /** SDK 上报的 GPS 信号弱状态 */
    @Volatile private var gpsWeak = false

    /**
     * 当前导航信号等级，给 UI 每秒轮询刷新用。
     *
     * 0 = 没信号 / 1 = 弱 / 2 = 正常。
     *
     * ★ 为什么不用 SDK 内置那个「卫星信号弱」：
     *   它是静态视图，进了弱信号状态后经常不自己恢复（用户反馈「不能自动刷新」）。
     *   这里用两个来源交叉判断：SDK 的 onGpsSignalWeak + 定位回调有没有断流，
     *   UI 每秒取一次，掉线 8 秒就降级，恢复定位立刻回正常。
     */
    fun signalLevel(): Int {
        if (!hasFix) return 0
        if (lastFixMs > 0 && System.currentTimeMillis() - lastFixMs > SIGNAL_LOST_MS) return 0
        return if (gpsWeak) 1 else 2
    }

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

                // ★ 红绿灯信号数据。V11.3 里 setTrafficSignalEnable 只打开数据源；
                //   官方倒计时视图还需要 setIsOpenTrafficLight("TLTRUE")。
                setTrafficLightEnabled(Prefs.navTrafficLight)

                // 多路径选择在算路成功后开启。此时候选路线已经就绪，SDK 才能把
                // 可点击的路线分支和切换入口挂到当前 AMapNaviView 上。
                runCatching { it.setMultipleRouteNaviMode(true) }
                    .onFailure { error ->
                        Dbg.log("多路径导航模式开启失败：${error.message}")
                    }

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
                        // 定位时间戳：UI 靠它判断信号有没有断流（GPS 丢了 SDK 不一定回调）
                        lastFixMs = System.currentTimeMillis()
                        gpsWeak = false
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
        Dbg.log("navigateTo chars=${destination.length} hasFix=$hasFix citySet=${!currentCity.isNullOrBlank()}")
        val n = navi
        if (n == null) {
            failCurrentAttempt("导航引擎未就绪")
            onState("导航引擎未就绪")
            voice.speak("导航引擎还没准备好", VoiceGuide.Priority.USER)
            return
        }
        if (!hasFix) {
            failCurrentAttempt("还没有定位")
            onState("正在定位，请稍候再试")
            voice.speak("还没定位到当前位置，稍等一下再说", VoiceGuide.Priority.USER)
            return
        }
        pendingDestination = destination
        pendingPlace = null
        // 新的一次导航 → 解除「停止中」标志
        stopRequested = false
        cancelPendingRoute = false
        hasNavCoord = false
        // ★ 中间态不弹提示（2026-11-04）：
        //   原来「正在解析」+「正在规划到X的路线」+「导航中→X」三条连着弹，
        //   1.2 秒内刷四五个 toast，看着像出故障（用户反馈：「是不是打架的进程」）。
        //   中间态 1~2 秒就过去了，弹出来只是闪一下；
        //   真正的反馈是语音播报 + 最后的「导航中 → X」。
        Dbg.log("开始解析目的地 chars=${destination.length}")
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
     * 直接用坐标导航（跳过地名解析）。
     *
     * ★ 为什么需要（2026-11-04）：
     *   语音说地址 → 搜出候选 → 用户确认 → 导航。
     *   这时候坐标**已经拿到了**（POI 搜索结果里有经纬度），
     *   再拿地名走一遍解析是多余的 ——
     *   而且可能解析到**另一个同名地点**（「张掖西站」vs「张掖西站(南广场)」）。
     *   直接用候选的坐标，百分之百是用户选的那个。
     */
    fun navigateToPoint(name: String, latitude: Double, longitude: Double, formatted: String = name) {
        Dbg.log("navigateToPoint chars=${name.length} hasFix=$hasFix")
        val n = navi
        if (n == null) {
            failCurrentAttempt("导航引擎未就绪")
            onState("导航引擎未就绪")
            voice.speak("导航引擎还没准备好", VoiceGuide.Priority.USER)
            return
        }
        if (!hasFix) {
            failCurrentAttempt("还没有定位")
            onState("正在定位，请稍候再试")
            voice.speak("还没定位到当前位置，稍等一下再说", VoiceGuide.Priority.USER)
            return
        }
        pendingDestination = name
        // 新的一次导航 → 解除「停止中」标志
        stopRequested = false
        cancelPendingRoute = false
        hasNavCoord = false
        onPlaceResolved(
            AmapSdkGeocoder.GeoPoint(
                latitude = latitude,
                longitude = longitude,
                formatted = formatted,
                city = currentCity.orEmpty(),
                district = ""
            )
        )
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
                        failCurrentAttempt("找不到目的地")
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
        val n = navi
        if (n == null) {
            failCurrentAttempt("导航引擎未就绪")
            return
        }

        // ★ ★ 距离兜底（2026-11-04）：
        //   地名解析可能跑到**同名的外地地点**上。
        //   实测：用户选的是张掖的「水勘院超市」，但高德 POI 搜索返回了
        //   **石家庄**的「槐中路135号水勘院(顺丰便利店)」—— 一路 1517 公里。
        //
        //   但「远」本身不是错 —— 用户说「石家庄火车站」就是要跨城。
        //   区别在于：**用户说的地名里有没有出现那个城市名**。
        //     「石家庄火车站」→ 结果 city=石家庄市 → 地名里有「石家庄」→ 放行 ✅
        //     「水勘院超市」  → 结果 city=石家庄市 → 地名里没有     → 拒绝 ❌
        val distM: Double =
            if (hasFix) distanceMeters(lastLat, lastLon, point.latitude, point.longitude) else -1.0
        if (distM > FAR_AWAY_METERS) {
            val dest = pendingDestination.orEmpty()
            // ★ 用户是不是**明确提到了**结果所在的地方？
            //   要比对省/市/区县三级 —— 用户可能说的是县名不是市名：
            //     「景泰县条山路金星家园」→ 结果在白银市**景泰县**
            //     只说「白银」比对的话就漏了（用户没提白银，提的是景泰县）。
            //   结果地址里拼了「甘肃省白银市景泰县…」，拿地名里的每个地名片段去比对。
            val placeWords = listOf(
                point.city,
                point.district,
                point.city.removeSuffix("市"),
                point.district.removeSuffix("县").removeSuffix("区")
            ).filter { it.isNotBlank() }
            val userMentioned = placeWords.any { w -> w.length >= 2 && dest.contains(w) }

            if (!userMentioned) {
                val km = (distM / 1000).toInt()
                Dbg.log(
                    "★ 拒绝解析结果：「$dest」命中了 ${point.city}${point.district} 的「${point.formatted}」" +
                        "（${km}公里），但用户没提到这个城市/区县"
                )
                pendingPlace = null
                failCurrentAttempt("目的地可能不在当前城市")
                onState("「$dest」搜到了${km}公里外${point.city}的同名地方，可能不对")
                voice.speak(
                    "$dest 搜到了${km}公里外${point.city}的同名地方，可能不是你要的，" +
                        "说清楚点在哪个城市，我再搜一次",
                    VoiceGuide.Priority.USER
                )
                return
            }
            Dbg.log("跨城导航放行：${point.city}${point.district}（用户提到了地名）")
        }

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
            // 中间态不弹 toast（见 resolvePlace 里的说明）—— 只在失败时提示
            if (ok) {
                Dbg.log("正在规划到 ${point.formatted} 的路线…")
            } else {
                failCurrentAttempt("算路请求发送失败")
                onState("算路请求发送失败")
                voice.speak("路线规划失败，换个说法试试", VoiceGuide.Priority.USER)
            }
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

    /**
     * 开始导航并**等路线算完**。
     *
     * ★ 为什么加这个（2026-11-04）：
     *   算路是异步的，`navigateTo()` 只是发起请求。
     *   工具如果不等就返回，模型拿到的就是「正在算路」这种中间态，
     *   会照着念 —— 用户听到「正在规划张掖西站」，
     *   但地图其实早就开始导航了，模型和地图对不上（用户反馈）。
     *
     * @return 路线信息描述（真实结果），超时则返回中间态描述
     */
    fun navigateAndAwait(destination: String, timeoutMs: Long = 15_000): NavigationAttempt {
        val wait = beginRouteWait()
        navigateTo(destination)
        return awaitAttempt(wait, timeoutMs)
    }

    /**
     * 开始导航并**等路线算完**（用精确坐标的版本）。
     */
    fun navigateToPointAndAwait(
        name: String,
        latitude: Double,
        longitude: Double,
        formatted: String = name,
        timeoutMs: Long = 15_000
    ): NavigationAttempt {
        val wait = beginRouteWait()
        navigateToPoint(name, latitude, longitude, formatted)
        return awaitAttempt(wait, timeoutMs)
    }

    private fun beginRouteWait(): RouteWait {
        val wait = RouteWait()
        val previous = routeWait
        routeWait = wait
        completeRouteWait(previous, NavigationAttempt(
            NavigationAttempt.Status.FAILED,
            message = "新的导航请求已开始"
        ))
        return wait
    }

    private fun awaitAttempt(wait: RouteWait, timeoutMs: Long): NavigationAttempt {
        val completed = try {
            wait.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            if (routeWait === wait) routeWait = null
            wait.result.compareAndSet(
                null,
                NavigationAttempt(NavigationAttempt.Status.TIMEOUT, message = "等待路线超时")
            )
            cancelPendingRoute = true
            if (!navigating) {
                stopRequested = true
                runCatching { navi?.stopNavi() }
            }
            Dbg.log("等待路线超时（${timeoutMs}ms）")
        }
        return wait.result.get()
            ?: NavigationAttempt(NavigationAttempt.Status.FAILED, message = "没有收到算路结果")
    }

    private fun completeRouteWait(wait: RouteWait?, attempt: NavigationAttempt) {
        if (wait == null) return
        if (routeWait === wait) routeWait = null
        wait.result.compareAndSet(null, attempt)
        wait.latch.countDown()
    }

    private fun failCurrentAttempt(message: String) {
        completeRouteWait(
            routeWait,
            NavigationAttempt(NavigationAttempt.Status.FAILED, message = message)
        )
    }

    private fun routeInfo(): String =
        if (pathRemainMeters > 0 && pathRemainSeconds > 0) {
            "全程${pathRemainMeters}米，约${Math.max(1, pathRemainSeconds / 60)}分钟"
        } else {
            ""
        }

    /**
     * 开关高德红绿灯信号源。
     *
     * `setTrafficSignalEnable(true)` 打开数据订阅；
     * `setIsOpenTrafficLight("TLTRUE")` 才是 V11.3 官方倒计时视图的总开关。
     */
    fun setTrafficLightEnabled(enabled: Boolean) {
        val n = navi ?: return
        // 三个开关相互独立，某一个失败时不能让后面的开关也被跳过。
        runCatching { n.setTrafficSignalEnable(enabled) }
            .onFailure { Dbg.log("红绿灯信号订阅失败：${it.message}") }
        runCatching { n.setTrafficStatusUpdateEnabled(enabled) }
            .onFailure { Dbg.log("红绿灯状态更新失败：${it.message}") }
        if (enabled) {
            runCatching { n.setIsOpenTrafficLight("TLTRUE") }
                .onFailure { Dbg.log("红绿灯倒计时开关失败：${it.message}") }
        }
        Dbg.log("红绿灯信号源 → $enabled")
    }

    override fun stop() {
        // ★ 先置「已请求停止」标志，再调 stopNavi()。
        //   SDK 的 stopNavi() 是个**软停止**：它先播「导航结束」，
        //   然后才真正停下来。这中间如果位置更新触发了重新算路，
        //   onCalculateRouteSuccess 会又来一次 → 又播一遍「准备出发」→
        //   用户听到的就是「退出导航后又重新开始一次导航」（用户反馈）。
        //
        //   置了标志，算路成功回调里就会忽略这次重算。
        stopRequested = true
        cancelPendingRoute = true
        failCurrentAttempt("导航已停止")
        runCatching { navi?.stopNavi() }
        navigating = false
        pendingDestination = null
        remainLights = 0
        stepRemainMeters = 0
        pathRemainSeconds = 0
        pathRemainMeters = 0
        hasNavCoord = false
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

    /**
     * 调试用：使用模拟导航。
     *
     * 真车不可能为了验证一次红绿灯倒计时专门开过多个路口；模拟导航可按固定
     * 速度推进，配合 `getStructuredInfoInNavi()` 的日志确认 SDK 是否真的下发秒数。
     */
    @Volatile
    var debugEmulator: Boolean = false

    /** 结构化红绿灯数据的日志限频，避免每个导航回调都写大段 JSON。 */
    @Volatile
    private var lastStructuredTrafficLogMs = 0L

    /** 当前坐标，给周边搜索用；没定位返回 null */
    fun currentLocation(): Pair<Double, Double>? =
        if (hasFix) lastLat to lastLon else null

    /**
     * 当前导航剩余路线，按行驶方向排列，给「沿途搜索」使用。
     *
     * `AMapNaviPath.getCoordList()` 保存的是当前活动路线的完整折线。导航过程中
     * 可能已经驶过前一段，所以先找离当前定位最近的路线点，再只返回它后面的部分，
     * 避免把已经经过的地点当成「沿途」结果。
     */
    fun remainingRoutePoints(): List<AmapSdkGeocoder.RoutePoint> {
        if (!navigating) return emptyList()
        val coords = runCatching { navi?.naviPath?.coordList }.getOrNull().orEmpty()
        if (coords.size < 2) return emptyList()

        val currentLat = if (hasNavCoord) navLat else lastLat
        val currentLon = if (hasNavCoord) navLon else lastLon
        val startIndex = if (hasFix || hasNavCoord) {
            var nearest = 0
            var nearestDistance = Double.MAX_VALUE
            coords.forEachIndexed { index, point ->
                val latDelta = point.latitude - currentLat
                val lonDelta = point.longitude - currentLon
                val distance = latDelta * latDelta + lonDelta * lonDelta
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearest = index
                }
            }
            nearest
        } else {
            0
        }

        val remaining = coords.drop(startIndex + 1).map {
            AmapSdkGeocoder.RoutePoint(it.latitude, it.longitude)
        }
        if (remaining.size < 2) return emptyList()
        Dbg.log(
            "沿途搜索路线截取 index=$startIndex/${coords.lastIndex} " +
                "points=${remaining.size + 1} source=${if (hasNavCoord) "navi" else "location"}"
        )
        return listOf(AmapSdkGeocoder.RoutePoint(currentLat, currentLon)) + remaining
    }

    /**
     * 当前导航实际指向的地点全称（带城市/区县），如「白银市景泰县金星家园」。
     * 用户说「导航错了」时用它重报，好让用户判断是哪里解析歪了。
     */
    val currentPlaceName: String?
        get() = pendingPlace?.let { describePlace(it) } ?: pendingDestination

    /** 释放：正常退出 Activity 时调用 */
    fun release() {
        failCurrentAttempt("导航页面已关闭")
        runCatching {
            navi?.removeAMapNaviListener(listener)
            navi?.stopNavi()
        }
        detachLocation()
        // AMapNavi.destroy() 在定位管理器仍被占用时会静默返回，单例和旧路线
        // 都会继续存活。必须先释放定位管理器，再销毁导航单例。
        runCatching { AMapNavi.releaseLocManager() }
            .onFailure { Dbg.log("释放高德定位管理器失败：${it.message}") }
        runCatching { AMapNavi.destroy() }
            .onFailure { Dbg.log("销毁高德导航单例失败：${it.message}") }
        navi = null
        navigating = false
        hasNavCoord = false
    }

    private fun detachLocation() {
        runCatching { locationClient?.stopLocation(); locationClient?.onDestroy() }
        locationClient = null
    }

    /**
     * 记录高德下发的红绿灯结构化数据。
     *
     * `NaviInfo.getRouteRemainLightCount()` 只是前方灯数，没有倒计时秒数。
     * V11.3 把倒计时和灯色放在结构化 JSON 中，回调频率较高，因此限频为
     * 2 秒一次，并在日志里截断超长字段。真机确认字段后，UI 才有可靠数据源。
     */
    private fun logStructuredTrafficInfo() {
        val n = navi ?: return
        val now = System.currentTimeMillis()
        if (now - lastStructuredTrafficLogMs < STRUCTURED_TRAFFIC_LOG_INTERVAL_MS) return
        lastStructuredTrafficLogMs = now
        val route = runCatching { n.structuredInfoInRoute }.getOrNull()
        val inNavi = runCatching { n.structuredInfoInNavi }.getOrNull()
        Dbg.log(
            "结构化导航数据 route=${route.limitForLog()} " +
                "nav=${inNavi.limitForLog()}"
        )
    }

    private fun String?.limitForLog(): String =
        if (this.isNullOrBlank()) "<empty>" else take(4_000)

    private val listener = object : NavListenerAdapter() {

        override fun onInitNaviSuccess() {
            Log.i(TAG, "导航引擎就绪，版本 ${AMapNavi.getVersion()}")
        }

        override fun onInitNaviFailure() {
            failCurrentAttempt("导航引擎初始化失败")
            onState("导航引擎初始化失败")
            voice.speak("导航引擎初始化失败", VoiceGuide.Priority.USER)
        }

        override fun onLocationChange(p0: AMapNaviLocation?) {
            if (stopRequested) return
            val coord = p0?.coord ?: return
            if (!coord.latitude.isFinite() || !coord.longitude.isFinite()) return
            if (coord.latitude == 0.0 && coord.longitude == 0.0) return
            navLat = coord.latitude
            navLon = coord.longitude
            hasNavCoord = true
        }

        override fun onCalculateRouteSuccess(p0: AMapCalcRouteResult?) {
            val n = navi
            if (n == null) {
                failCurrentAttempt("导航引擎未就绪")
                return
            }
            // ★ 用户已经请求停止导航了 —— 这次算路结果**作废**。
            //   SDK 的 stopNavi() 是软停止（先播「导航结束」再真正停），
            //   这中间位置更新会触发一次重算，如果照单全收就会又播一遍
            //   「准备出发」—— 用户听到的就是「退出导航后又重新开始导航」（用户反馈）。
            if (stopRequested) {
                Dbg.log("算路结果作废（用户已请求停止导航）")
                failCurrentAttempt("算路结果已取消")
                return
            }
            if (cancelPendingRoute) {
                cancelPendingRoute = false
                Dbg.log("算路结果作废（等待方已超时取消）")
                return
            }
            // 只记录候选数量，**不要**去 selectRouteId 遍历。
            // 遍历会把当前路线切到最后一条，而且多路径模式下 SDK 自己也会来回切，
            // 叠在一起就是导航频繁改道（实测故障）。
            Dbg.log("onCalculateRouteSuccess 候选=${p0?.getRouteid()?.size ?: 0}")

            val path = n.naviPath
            pathRemainSeconds = path?.allTime ?: 0
            pathRemainMeters = path?.allLength ?: 0

            // 候选路线刚生成时再开启一次。部分版本在初始化阶段还没有路线数据，
            // 只在 start() 调用会导致导航中仍只保留主路线。
            runCatching { n.setMultipleRouteNaviMode(true) }
                .onFailure { Dbg.log("多路径导航模式开启失败：${it.message}") }

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
            if (debugEmulator) {
                n.setEmulatorNaviSpeed(80)
            }
            val started = n.startNavi(
                if (debugEmulator) NaviType.EMULATOR else NaviType.GPS
            )
            navigating = started || wasNavigating
            Dbg.log("startNavi=$started pathLen=${path?.allLength} pathTime=${path?.allTime} wasNav=$wasNavigating")

            if (!started && !wasNavigating) {
                failCurrentAttempt("启动导航失败")
                onState("启动导航失败")
                onHud(NavHud(navigating = false))
                return
            }

            // 部分机型/版本在算路完成后才建立信号订阅，启动导航后再推一次。
            // 这是官方倒计时气泡能拿到数据的必要开关，重复调用是幂等的。
            setTrafficLightEnabled(Prefs.navTrafficLight)

            completeRouteWait(
                routeWait,
                NavigationAttempt(
                    status = if (wasNavigating) {
                        NavigationAttempt.Status.REROUTED
                    } else {
                        NavigationAttempt.Status.STARTED
                    },
                    routeInfo = routeInfo()
                )
            )

            // ★ 算路完成 → 通知界面「先看全程」（2026-11-04）
            //   改道重算时**不打断**：用户正开着导航呢，突然切全程很烦。
            if (started) {
                onRouteReady(pathRemainMeters, pathRemainSeconds, wasNavigating)
            }

            val place = pendingPlace
            val where = place?.let { describePlace(it) } ?: (pendingDestination ?: "目的地")
            // 目的地不在当前城市 —— 这种多半是跨市长途，必须让用户第一时间听出来对不对
            val farAway = place != null &&
                place.city.isNotBlank() &&
                !currentCity.isNullOrBlank() &&
                place.city != currentCity
            onState(if (navigating) "导航中 → $where" else "启动导航失败")
            if (navigating) {
                // ★ ★ 导航开始的播报**完全交给高德**（2026-11-04）
                //   高德自己会播：「准备出发，全程2.6公里，大约9分钟，左转，随后…」
                //   —— 完整、准确、带转向。
                //
                //   以前我们自己**也**播一句「开始导航去X，全程2.6公里」，
                //   两条都是 NAV 优先级 → 互相 tts.stop() 打断
                //   → 用户听到的是断断续续的（用户反馈：「播报会被中断」）。
                //
                //   ★ 跨市提醒也不额外播：
                //     高德的播报是 SDK 内部直接调 TTS，不经过我们的 VoiceGuide，
                //     我们 tts.stop() 拦不住它 —— 实测我们的提醒 0.65 秒就被打断。
                //     而且高德那句「全程1516公里，17小时」本身就会让用户意识到是长途，
                //     界面提示「导航中 → 石家庄市桥西区石家庄站」也带了城市全称。
                Dbg.log("导航播报交给高德（我们不重复）跨市=$farAway 改道=$wasNavigating destinationChars=${where.length}")
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
            if (stopRequested || cancelPendingRoute) {
                Dbg.log("算路失败回调作废（导航已停止或请求已取消）")
                return
            }
            val code = p0?.errorCode ?: -1
            val desc = p0?.errorDescription ?: "未知错误"
            Dbg.log("算路失败 code=$code desc=$desc")
            Log.w(TAG, "算路失败 code=$code desc=$desc")
            navigating = false
            completeRouteWait(
                routeWait,
                NavigationAttempt(
                    NavigationAttempt.Status.FAILED,
                    message = desc
                )
            )
            onState("算路失败：$desc（$code）")
            voice.speak("路线规划失败，$desc", VoiceGuide.Priority.USER)
            onHud(NavHud(navigating = false))
        }

        override fun onNaviInfoUpdate(p0: NaviInfo?) {
            if (stopRequested) {
                Dbg.log("忽略退出后的 NaviInfo 回调")
                return
            }
            val info = p0 ?: return
            logStructuredTrafficInfo()
            Dbg.log(
                "NaviInfo step=${info.curStepRetainDistance} road=${info.nextRoadName} " +
                    "remain=${info.pathRetainDistance}/${info.pathRetainTime} " +
                    "lights=${info.routeRemainLightCount}"
            )
            stepRemainMeters = info.curStepRetainDistance
            pathRemainSeconds = info.pathRetainTime
            pathRemainMeters = info.pathRetainDistance
            remainLights = runCatching { info.routeRemainLightCount }.getOrDefault(0)
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

        override fun onTrafficStatusUpdate() {
            if (stopRequested) return
            logStructuredTrafficInfo()
        }

        /** SDK 生成的导航播报文本，接管到我们自己的 TTS 队列（NAV 优先级最高） */
        override fun onGetNavigationText(p0: String?) {
            if (stopRequested) return
            val text = p0?.takeIf { it.isNotBlank() } ?: return
            Dbg.log("导航播报 chars=${text.length}")
            voice.speak(text, VoiceGuide.Priority.NAV)
        }

        override fun onArriveDestination() {
            if (stopRequested) return
            navigating = false
            onState("已到达目的地")
            voice.speak("已到达目的地", VoiceGuide.Priority.NAV)
            onHud(NavHud(navigating = false))
        }

        override fun onReCalculateRouteForYaw() {
            if (stopRequested) return
            voice.speak("路线已重新规划", VoiceGuide.Priority.NAV)
        }

        override fun onReCalculateRouteForTrafficJam() {
            if (stopRequested) return
            voice.speak("前方拥堵，已为你换路", VoiceGuide.Priority.NAV)
        }

        /**
         * GPS 信号弱状态变化。
         *
         * ★ 2026-11-03：接到 UI 上去做实时信号指示。
         *   原来只看 SDK 内置那个静态标签，进了弱信号就一直挂着「卫星信号弱」，
         *   出了隧道也不会恢复 —— 用户说的「信号显示不能自动刷新」就是这个。
         */
        override fun onGpsSignalWeak(p0: Boolean) {
            gpsWeak = p0
            if (p0) Dbg.log("GPS 信号弱")
        }

        override fun onGpsOpenStatus(p0: Boolean) {
            Dbg.log("GPS 开关状态=$p0")
            if (!p0) gpsWeak = true
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

        const val STRUCTURED_TRAFFIC_LOG_INTERVAL_MS = 2_000L

        /**
         * 解析结果离当前位置多远算「可疑」（米）。
         *
         * ★ 为什么是 100 公里：
         *   市内跨区也就几十公里；隔壁市 100~200 公里。
         *   100 公里这个线，能挡住「同名的外地地点」这种离谱误匹配，
         *   又不会误伤正常的跨城长途（那种地名里会带省市，会走另一条路）。
         */
        const val FAR_AWAY_METERS = 100_000.0

        /** 两点间直线距离（米） */
        private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }

        /** 定位超过这么久没更新，就认为信号断了（隧道、地下车库） */
        const val SIGNAL_LOST_MS = 8_000L
    }
}
