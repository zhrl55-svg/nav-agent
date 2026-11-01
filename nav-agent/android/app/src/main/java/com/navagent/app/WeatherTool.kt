package com.navagent.app

import com.navagent.core.LlmTool
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 天气查询（高德 Web 服务 API）。
 *
 * 为什么选它：这个 Key 已经为了地理编码打进包里了，天气是同一 Key 下的接口，
 * **零额外配置、零额外成本**。而车里问得最多的「实时信息」就是天气。
 *
 * 调两个接口：base 拿实况（现在几度、湿度、风），all 拿未来几天预报。
 * 单拿 all 只有预报没有实况，用户问「现在冷不冷」就答不准。
 */
class WeatherTool(
    /** 当前城市（如「张掖市」）。模型没指定城市时用它兜底 */
    private val currentCity: () -> String?
) : LlmTool {

    override val name = "get_weather"

    override val description =
        "查询中国城市的实时天气和未来几天预报。用户问天气、气温、下不下雨、冷不冷、" +
            "穿什么、要不要带伞、明天怎么样时调用。"

    override fun parametersJson(): String = """
        {"type":"object","properties":{"city":{"type":"string","description":"中国城市名，如「张掖市」「北京市」。留空表示用用户当前位置的城市"}},"required":[]}
    """.trimIndent()

    override fun execute(argumentsJson: String): String {
        val asked = runCatching { JSONObject(argumentsJson).optString("city") }
            .getOrDefault("")
            .trim()
        val city = asked.ifBlank { currentCity()?.trim().orEmpty() }

        if (city.isBlank()) {
            return "不知道用户现在在哪个城市。请先问一句「你在哪个城市」再查。"
        }
        return fetch(city) ?: "天气接口暂时查不到${city}的数据"
    }

    private fun fetch(city: String): String? {
        val encoded = URLEncoder.encode(city, "UTF-8")

        // 实况和预报是两个独立接口，串行要 400ms，并发只要 200ms。
        // 语音场景每一百毫秒都能感知到，值得。
        val liveTask = java.util.concurrent.FutureTask<JSONObject?> { query("&city=$encoded&extensions=base") }
        val fcTask = java.util.concurrent.FutureTask<JSONObject?> { query("&city=$encoded&extensions=all") }
        Thread(liveTask, "weather-live").apply { isDaemon = true }.start()
        Thread(fcTask, "weather-forecast").apply { isDaemon = true }.start()
        val live = runCatching { liveTask.get(8, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        val forecast = runCatching { fcTask.get(8, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()

        val sb = StringBuilder()

        // 实况
        live?.optJSONArray("lives")?.optJSONObject(0)?.let { l ->
            sb.append(l.optString("city")).append(" 实况：")
                .append(l.optString("weather"))
                .append(" ").append(l.optString("temperature")).append("℃")
            l.optString("winddirection").takeIf { it.isNotBlank() }?.let {
                sb.append(" ").append(it).append("风").append(l.optString("windpower"), "级")
            }
            l.optString("humidity").takeIf { it.isNotBlank() }?.let {
                sb.append(" 湿度").append(it).append("%")
            }
            sb.append("（").append(l.optString("reporttime")).append(" 更新）\n")
        }

        // 预报
        forecast?.optJSONArray("forecasts")?.optJSONObject(0)?.let { f ->
            if (sb.isEmpty()) sb.append(f.optString("city")).append("\n")
            val casts = f.optJSONArray("casts")
            if (casts != null) {
                val n = minOf(casts.length(), 4)
                for (i in 0 until n) {
                    val c = casts.optJSONObject(i) ?: continue
                    sb.append(if (i == 0) "今天" else if (i == 1) "明天" else c.optString("date"))
                        .append("：白天").append(c.optString("dayweather"))
                        .append(" ").append(c.optString("daytemp")).append("℃")
                        .append("，夜间").append(c.optString("nightweather"))
                        .append(" ").append(c.optString("nighttemp")).append("℃")
                        .append("\n")
                }
            }
        }

        val text = sb.toString().trim()
        if (text.isEmpty()) {
            Dbg.log("天气：$city 没有任何数据")
            return null
        }
        return text
    }

    private fun query(tail: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val url = "https://restapi.amap.com/v3/weather/weatherInfo" +
                "?key=${BuildConfig.AMAP_WEB_KEY}$tail"
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optString("status") != "1") {
                Dbg.log("天气查询失败 info=${json.optString("info")} code=${json.optString("infocode")}")
                return null
            }
            json
        } catch (t: Throwable) {
            Dbg.log("天气异常 ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
