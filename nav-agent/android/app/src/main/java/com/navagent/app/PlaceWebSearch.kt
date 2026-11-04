package com.navagent.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 地点候选搜索 —— **同步**，走高德 Web API。
 *
 * ## ★ 为什么不用 SDK 的 PoiSearch（2026-11-04）
 *
 * 试过 `AmapSdkGeocoder.searchKeyword`，在导航工具里同步等回调，结果**回调 8 秒都不来**：
 *   11:10:38 发起 → 11:10:46 超时返回 0 条 → 11:10:46.9 回调才姗姗来迟。
 * 而同样的调用放在搜索栏里（用户手动打字触发）几百毫秒就来。
 *
 * 原因没查清楚，但结论很清楚：**工具链路上这个异步回调不可靠**。
 * 工具必须同步返回结果给模型，不能赌回调什么时候来。
 *
 * Web API 的好处：
 *   - 同步 HTTP，工具在子线程直接调，简单可靠
 *   - 支持 `citylimit=false`，跨城地名能搜到（SDK 那边 citylimit 关不干净）
 *   - AMAP_WEB_KEY 已经有了（WeatherTool 在用）
 *
 * 代价：多一次网络往返（~200~500ms），可以接受。
 *
 * ★ 注意：这个类**只能在子线程调用**（会发 HTTP）。
 *   LlmTool.execute 的契约就是子线程，正合适。
 */
object PlaceWebSearch {

    /**
     * 按「优先本地」的规则搜地点。
     *
     * ★★★ 核心策略（2026-11-04 实测定稿）：
     *   **`city=当前城市` + `citylimit=false`** —— city 只作**偏置**，不作硬限制。
     *
     *   高德自己的排序在这个组合下完全正确（实测）：
     *     「水勘院超市」        → 张掖市甘州区的水勘院超市 ✅（本地优先）
     *     「景泰县条山路金星家园」→ 白银市景泰县的金星家园 ✅（跨城也对）
     *     「张掖西站」          → 张掖市甘州区的张掖西站 ✅
     *
     *   对比其它两种组合：
     *     · citylimit=true  → 只搜本市，跨城地名一条都搜不到
     *       （「景泰县条山路金星家园」→ 只能搜到本市的「金星灯饰」）
     *     · 不传 city       → 全国搜，「水勘院超市」直接命中石家庄的同名地点
     *
     * ★ 为什么不用自己判断「地名带不带行政区划」：
     *   试过用正则判断，结果「水勘院超**市**」里的「市」被当成行政区划，
     *   跳过本地搜索直接全国搜 → 命中石家庄，一路 1517 公里。
     *   猜地名规则太脆，不如让高德自己排 —— 它有全量 POI 数据的语义理解。
     *
     * @param city 当前城市（从定位反查得到），可空 —— 空则纯全国搜
     */
    fun searchLocalFirst(
        keyword: String,
        city: String?,
        limit: Int = 5
    ): List<AmapSdkGeocoder.Poi> {
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()
        val pois = search(q, city, cityLimit = false, limit = limit)
        Dbg.log(
            "地点解析「$q」city=$city → ${pois.size} 条：" +
                pois.joinToString { "${it.name}(${it.address.take(6)})" }
        )
        return pois
    }


    /**
     * 搜地点候选。
     *
     * @param city 城市偏置，可空。空 = 全国搜
     * @param cityLimit 是否限定在 city 内。跨城地名必须传 false
     * @return 候选列表（按相关度，高德已排好序）
     */
    fun search(
        keyword: String,
        city: String? = null,
        cityLimit: Boolean = true,
        limit: Int = 5
    ): List<AmapSdkGeocoder.Poi> {
        val q = keyword.trim()
        if (q.isEmpty()) return emptyList()
        val tail = buildString {
            append("?key=").append(BuildConfig.AMAP_WEB_KEY)
            append("&keywords=").append(java.net.URLEncoder.encode(q, "UTF-8"))
            append("&extensions=all")
            append("&offset=").append(limit)
            append("&page=1")
            if (!city.isNullOrBlank()) append("&city=").append(java.net.URLEncoder.encode(city, "UTF-8"))
            append("&citylimit=").append(if (cityLimit) "true" else "false")
        }
        val json = query(tail) ?: return emptyList()
        if (json.optString("status") != "1") {
            Dbg.log("地点搜索失败 info=${json.optString("info")} code=${json.optString("infocode")}")
            return emptyList()
        }
        val poisArr = json.optJSONArray("pois") ?: return emptyList()
        val out = ArrayList<AmapSdkGeocoder.Poi>(poisArr.length())
        for (i in 0 until poisArr.length()) {
            val o = poisArr.optJSONObject(i) ?: continue
            val location = o.optString("location")
            if (location.isBlank()) continue
            val parts = location.split(",")
            if (parts.size < 2) continue
            val lon = parts[0].toDoubleOrNull() ?: continue
            val lat = parts[1].toDoubleOrNull() ?: continue
            out.add(
                AmapSdkGeocoder.Poi(
                    name = o.optString("name").ifBlank { q },
                    distance = o.optString("distance").toIntOrNull() ?: 0,
                    latitude = lat,
                    longitude = lon,
                    address = listOfNotNull(
                        // ★ 字段名是 pname/cityname/adname，不是 province/city！
                        //   Web API 的响应里没有 province/city 这两个 key，
                        //   取错就全是空串（实测踩过：地址拼不出来、城市判断失效）。
                        o.optString("pname").takeIf { it.isNotBlank() },
                        o.optString("cityname").takeIf { it.isNotBlank() },
                        o.optString("adname").takeIf { it.isNotBlank() },
                        o.optString("address").takeIf { it.isNotBlank() }
                    ).distinct().joinToString(""),
                    city = o.optString("cityname"),
                    district = o.optString("adname")
                )
            )
        }
        Dbg.log("地点搜索「$q」cityLimit=$cityLimit → ${out.size} 条：${out.joinToString { it.name }}")
        return out
    }

    private fun query(tail: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val url = "https://restapi.amap.com/v3/place/text$tail"
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (t: Throwable) {
            Dbg.log("地点搜索异常 ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
