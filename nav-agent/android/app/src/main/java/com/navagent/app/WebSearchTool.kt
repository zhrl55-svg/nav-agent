package com.navagent.app

import com.navagent.core.LlmTool
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通用 Web 搜索（Tavily）。
 *
 * 为什么是 Tavily：MiniMax 的 API 没有内置联网，只能自己接搜索服务。
 * 实测这台手机能直连 `api.tavily.com`（23.21.204.74，10ms），不需要挂代理；
 * 而且它的 `include_answer` 会先给一段自总结，语音场景很省 —— 模型不用自己读十条网页。
 *
 * 它和 [NearbyTool] 的分工，靠 description 划清楚：
 *   search_nearby → 地理实体（加油站、餐厅、停车场），走高德 POI，带距离
 *   web_search    → 新闻、事件、价格、政策这类「网上才有」的信息
 * 两个都不管天气（那是 [WeatherTool]）。
 */
class WebSearchTool(private val apiKey: () -> String) : LlmTool {

    override val name = "web_search"

    override val description =
        "搜索互联网，查实时信息和事实：新闻、事件进展、价格、政策法规、人物近况、公司资料、" +
            "以及任何你不确定或可能过时的知识。可按 topic=general/news 和 time_range=day/week/month/year 限定范围。\n" +
            "不要用它查本地店铺（加油站/餐厅/停车场用 search_nearby），也不要查天气（用 get_weather）。"

    override fun parametersJson(): String = """
        {"type":"object","properties":{
          "query":{"type":"string","description":"搜索主题或关键词，中文查询尽量保留具体实体和时间"},
          "topic":{"type":"string","enum":["general","news"],"description":"常规资料用 general，近期新闻用 news"},
          "time_range":{"type":"string","enum":["day","week","month","year"],"description":"近期新闻可指定时间范围；不需要时省略"}
        },"required":["query"]}
    """.trimIndent()

    override fun execute(argumentsJson: String): String {
        val query = runCatching { JSONObject(argumentsJson).optString("query") }
            .getOrDefault("")
            .trim()
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return "搜索参数格式错误，未执行搜索"
        if (query.isBlank()) return "搜索关键词为空，未执行搜索"
        val topic = args.optString("topic", "general").takeIf { it in setOf("general", "news") } ?: "general"
        val timeRange = args.optString("time_range").takeIf { it in setOf("day", "week", "month", "year") }

        val key = apiKey().trim()
        if (key.isBlank()) {
            Dbg.log("web_search 未执行：Tavily Key 未配置")
            return "联网搜索不可用：没有配置 Tavily 搜索 Key。请在设置→联网搜索配置 Key；本次没有联网，不能把模型记忆当作实时搜索结果。"
        }

        val payload = JSONObject()
            .put("query", query)
            .put("topic", topic)
            .put("search_depth", "basic")
            .put("max_results", 5)
            .put("include_answer", true)
            .put("include_published_date", true)
            .apply { if (timeRange != null) put("time_range", timeRange) }
            .toString()

        var conn: HttpURLConnection? = null
        return try {
            val t0 = System.currentTimeMillis()
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Dbg.log("web_search($query) HTTP $code ${System.currentTimeMillis() - t0}ms")

            if (code !in 200..299) {
                Dbg.log("web_search 失败 ${body.take(200)}")
                return when (code) {
                    401, 403 -> "联网搜索失败：Tavily Key 无效或没有权限；本次没有取得搜索结果。请明确告知用户未能核实实时信息。"
                    429 -> "联网搜索失败：Tavily 额度不足；本次没有取得搜索结果。请明确告知用户未能核实实时信息。"
                    else -> "联网搜索失败（HTTP $code）；本次没有取得搜索结果。请明确告知用户未能核实实时信息，再谨慎提供可能过时的背景知识。"
                }
            }

            val json = JSONObject(body)
            val out = buildString {
                val answer = json.optString("answer").trim()
                if (answer.isNotEmpty()) append(answer).append("\n\n")

                val results = json.optJSONArray("results")
                if (results != null) {
                    var appended = 0
                    for (i in 0 until minOf(results.length(), 3)) {
                        val r = results.optJSONObject(i) ?: continue
                        if (appended == 0) append("来源：\n")
                        appended++
                        append("- ").append(r.optString("title").take(90))
                        val url = r.optString("url")
                        if (url.isNotBlank()) append("（").append(url.take(140)).append("）")
                        val published = r.optString("published_date").takeIf { it.isNotBlank() && it != "null" }
                        if (published != null) append(" · 发布/更新：").append(published)
                        append("\n")
                        val content = r.optString("content").trim()
                        if (content.isNotEmpty()) {
                            append("  ").append(content.take(420)).append("\n")
                        }
                    }
                }
            }.trim()

            return out.ifBlank { "没搜到「$query」的相关内容" }
        } catch (t: Throwable) {
            Dbg.log("web_search 异常 ${t.javaClass.simpleName}: ${t.message}")
            "搜索出错了：${t.message ?: t.javaClass.simpleName}"
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.tavily.com/search"
    }
}
