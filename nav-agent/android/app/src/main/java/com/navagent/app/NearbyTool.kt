package com.navagent.app

import android.content.Context
import com.navagent.core.LlmTool
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 周边搜索工具（Function Calling）。
 *
 * 为什么全部交给模型：
 *   `IntentParser` 里原来有一条 `附近|周边` 的正则，但用户实际说法是
 *   「周围有什么好吃的」「搜索一下西站附近的咖啡厅」「旁边有加油站吗」
 *   「在目的地附近找一下停车场」—— 正则穷举不完，漏掉的全部掉给模型闲聊，
 *   用户以为"搜索功能根本没做"。
 *
 * 为什么可以放心交给模型：
 *   漏判的代价只是变成闲聊（不像「导航去X」漏了会走错路），
 *   而且这个工具本身有定位兜底和超时保护，不会把会话卡死。
 *
 * ★ 为什么这里能用 latch 把异步 SDK 转成同步：
 *   工具是在 `OpenAiLlm` 的独立线程里跑的（FutureTask + 8 秒超时），
 *   所以在工具内部阻塞等待网络结果是安全的，不会卡主线程。
 *   之前用 Web API 时是「同步 HTTP + 自己开子线程」，现在 SDK 本身是异步回调，
 *   正好反过来：用 CountDownLatch 把回调转回同步，调用方代码不用大改。
 */
class NearbyTool(
    private val context: Context,
    private val location: () -> Pair<Double, Double>?,
    /** 当前导航剩余路线；不在导航中时返回空列表。 */
    private val route: () -> List<AmapSdkGeocoder.RoutePoint>? = { null },
    private val radius: () -> Int,
    /** 记住最近一次结果，用户接着说「去」时能直接导航过去 */
    private val onResult: (List<AmapSdkGeocoder.Poi>) -> Unit = {}
) : LlmTool {

    override val name = "search_nearby"

    override val description =
        "搜索用户当前位置附近，或当前导航路线沿途的店铺和地点。" +
            "用户说附近、周边、周围、旁边、跟前时用 scope=current；" +
            "用户说沿途、顺路、路上时用 scope=route。只能搜地理实体" +
            "（加油站/餐厅/停车场/银行/药店），搜不了新闻或知识。"

    override fun parametersJson(): String = """
        {"type":"object","properties":{"keyword":{"type":"string","description":"要找什么，用简短名词，如「加油站」「餐厅」「停车场」「药店」「咖啡厅」"},"scope":{"type":"string","enum":["current","route"],"description":"current=当前位置周边；route=当前导航路线沿途。用户说沿途或顺路时必须传 route"}},"required":["keyword","scope"]}
    """.trimIndent()

    override fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
        val keyword = args.optString("keyword").trim()
        val scope = args.optString("scope", "current")
            .trim()
            .lowercase(Locale.ROOT)

        if (keyword.isBlank()) {
            return "需要知道用户想找什么（比如加油站、餐厅、停车场）。可以先反问一句。"
        }

        if (scope == "route") {
            return searchAlongRoute(keyword)
        }
        return searchAroundCurrent(keyword)
    }

    private fun searchAroundCurrent(keyword: String): String {
        val loc = location()
        if (loc == null) {
            return "还没定位到用户位置。请让用户先点顶部「导航」打开地图，定位拿到后再查。"
        }

        val r = radius()
        val list = awaitAround(loc.first, loc.second, keyword, r)
        Dbg.log("search_nearby($keyword) radius=$r → ${list.size} 条")

        if (list.isEmpty()) {
            val km = if (r >= 1000) "${r / 1000}公里" else "${r}米"
            return "附近${km}内没找到${keyword}"
        }

        // 记下来，用户接着只说「去」也能走
        onResult(list)

        return buildString {
            append("附近的").append(keyword).append("：\n")
            list.forEach {
                append("- ").append(it.name)
                if (it.distance >= 0) append("，").append(formatDistance(it.distance))
                append("\n")
            }
        }.trim()
    }

    private fun searchAlongRoute(keyword: String): String {
        val r = radius().coerceIn(200, 3_000)
        val points = route()?.filter {
            it.latitude.isFinite() && it.longitude.isFinite()
        }.orEmpty()
        if (points.size < 2) {
            return "现在不在导航中，拿不到剩余路线。请先规划路线并开始导航，再搜沿途的${keyword}。"
        }

        val list = awaitAlongRoute(points, keyword, r)
        Dbg.log(
            "search_nearby(route, $keyword) radius=$r routePoints=${points.size} → ${list.size} 条"
        )
        if (list.isEmpty()) {
            return "导航路线沿途${formatDistance(r)}内没找到${keyword}"
        }

        onResult(list)
        return buildString {
            append("导航路线沿途的").append(keyword)
                .append("（路线两侧").append(formatDistance(r)).append("内）：\n")
            list.forEach {
                append("- ").append(it.name)
                if (it.distance >= 0) {
                    append("，前方").append(formatDistance(it.distance))
                }
                append("\n")
            }
        }.trim()
    }

    private fun awaitAlongRoute(
        route: List<AmapSdkGeocoder.RoutePoint>,
        keyword: String,
        radiusMeters: Int
    ): List<AmapSdkGeocoder.Poi> {
        val raw = if (AmapSdkGeocoder.supportsAlongRouteSearch(keyword)) {
            awaitOfficialRouteSearch(route, keyword, radiusMeters)
        } else {
            awaitSampledRouteSearch(route, keyword, radiusMeters)
        }
        return normalizeRouteResults(route, raw, radiusMeters)
    }

    private fun awaitOfficialRouteSearch(
        route: List<AmapSdkGeocoder.RoutePoint>,
        keyword: String,
        radiusMeters: Int
    ): List<AmapSdkGeocoder.Poi> {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<List<AmapSdkGeocoder.Poi>>(emptyList())
        AmapSdkGeocoder.searchAlongRoute(
            context, route, keyword, radiusMeters, 12
        ) { list ->
            holder.set(list)
            latch.countDown()
        }
        return try {
            latch.await(6, TimeUnit.SECONDS)
            holder.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        }
    }

    /**
     * 高德 RoutePOISearch 只支持固定类别。停车场、药店等关键词改为沿路线取圆心，
     * 并行做周边搜索，再把结果投影回路线并按「前方多少米」排序。
     */
    private fun awaitSampledRouteSearch(
        route: List<AmapSdkGeocoder.RoutePoint>,
        keyword: String,
        radiusMeters: Int
    ): List<AmapSdkGeocoder.Poi> {
        val centers = sampleRouteCenters(route, radiusMeters)
        if (centers.isEmpty()) return emptyList()

        val latch = CountDownLatch(centers.size)
        val holder = CopyOnWriteArrayList<AmapSdkGeocoder.Poi>()
        centers.forEach { center ->
            AmapSdkGeocoder.searchAroundList(
                context,
                center.latitude,
                center.longitude,
                keyword,
                radiusMeters,
                4
            ) { list ->
                holder.addAll(list)
                latch.countDown()
            }
        }
        try {
            latch.await(6, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return holder.toList()
    }

    /**
     * 最多取 8 个均匀路线圆心。6 公里间距在默认 3 公里搜索半径下能覆盖路线；
     * 长途路线会扩大间距，避免一次语音工具调用发出几十个网络请求。
     */
    private fun sampleRouteCenters(
        route: List<AmapSdkGeocoder.RoutePoint>,
        radiusMeters: Int,
        maxSamples: Int = 8
    ): List<AmapSdkGeocoder.RoutePoint> {
        if (route.size < 2) return route
        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cumulative[i] = cumulative[i - 1] + geoDistanceMeters(
                route[i - 1].latitude,
                route[i - 1].longitude,
                route[i].latitude,
                route[i].longitude
            )
        }
        val total = cumulative.last()
        if (total <= 0.0) return listOf(route.first())
        val step = maxOf(radiusMeters * 2.0, total / (maxSamples - 1))
        val out = ArrayList<AmapSdkGeocoder.RoutePoint>(maxSamples)
        var target = 0.0
        var index = 0
        while (out.size < maxSamples && target <= total) {
            while (index < cumulative.lastIndex && cumulative[index + 1] < target) index++
            out.add(route[index])
            target += step
        }
        return out
    }

    private fun normalizeRouteResults(
        route: List<AmapSdkGeocoder.RoutePoint>,
        raw: List<AmapSdkGeocoder.Poi>,
        radiusMeters: Int
    ): List<AmapSdkGeocoder.Poi> {
        val maxOffRoute = radiusMeters + 300.0
        return raw.mapNotNull { poi ->
            val position = positionOnRoute(route, poi.latitude, poi.longitude)
                ?: return@mapNotNull null
            // 路线首点是当前车标。投影在 0 米处的 POI 要么在当前位置旁边，
            // 要么已经被甩在后面；用户要的是「前方」，这类结果不能冒充沿途结果。
            if (position.offRouteMeters > maxOffRoute || position.alongRouteMeters < 1.0) null
            else poi.copy(distance = position.alongRouteMeters.toInt())
        }
            .distinctBy {
                "${it.name.trim()}@${String.format(Locale.ROOT, "%.4f", it.latitude)}," +
                    String.format(Locale.ROOT, "%.4f", it.longitude)
            }
            .sortedBy { it.distance }
            .take(5)
    }

    private data class RoutePosition(
        val alongRouteMeters: Double,
        val offRouteMeters: Double
    )

    private fun positionOnRoute(
        route: List<AmapSdkGeocoder.RoutePoint>,
        latitude: Double,
        longitude: Double
    ): RoutePosition? {
        if (route.size < 2) return null
        val origin = route.first()
        val metersPerLongitude = 111_320.0 * cos(Math.toRadians(origin.latitude))
        fun x(point: AmapSdkGeocoder.RoutePoint) =
            (point.longitude - origin.longitude) * metersPerLongitude
        fun y(point: AmapSdkGeocoder.RoutePoint) =
            (point.latitude - origin.latitude) * 111_320.0

        val px = (longitude - origin.longitude) * metersPerLongitude
        val py = (latitude - origin.latitude) * 111_320.0
        var traversed = 0.0
        var best: RoutePosition? = null
        for (i in 0 until route.lastIndex) {
            val ax = x(route[i])
            val ay = y(route[i])
            val bx = x(route[i + 1])
            val by = y(route[i + 1])
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val segmentLength = hypot(dx, dy)
            val t = if (lengthSquared <= 0.0) {
                0.0
            } else {
                (((px - ax) * dx + (py - ay) * dy) / lengthSquared)
                    .coerceIn(0.0, 1.0)
            }
            val nearestX = ax + dx * t
            val nearestY = ay + dy * t
            val offRoute = hypot(px - nearestX, py - nearestY)
            if (best == null || offRoute < best.offRouteMeters) {
                best = RoutePosition(
                    alongRouteMeters = traversed + segmentLength * t,
                    offRouteMeters = offRoute
                )
            }
            traversed += segmentLength
        }
        return best
    }

    private fun geoDistanceMeters(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): Double {
        val meanLat = Math.toRadians((fromLat + toLat) / 2.0)
        val dx = (toLon - fromLon) * 111_320.0 * cos(meanLat)
        val dy = (toLat - fromLat) * 111_320.0
        return hypot(dx, dy)
    }

    private fun formatDistance(meters: Int): String =
        if (meters >= 1000) {
            String.format(Locale.CHINA, "%.1f公里", meters / 1000.0)
        } else {
            "${meters}米"
        }

    /**
     * 等高德周边搜索回调。
     *
     * 超时比工具层的 8 秒短一点，留出余量给工具层拼字符串。
     */
    private fun awaitAround(
        lat: Double,
        lon: Double,
        keyword: String,
        radiusMeters: Int
    ): List<AmapSdkGeocoder.Poi> {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<List<AmapSdkGeocoder.Poi>>(emptyList())
        AmapSdkGeocoder.searchAroundList(
            context, lat, lon, keyword, radiusMeters, 3
        ) { list ->
            holder.set(list)
            latch.countDown()
        }
        return try {
            latch.await(6, TimeUnit.SECONDS)
            holder.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        }
    }
}
