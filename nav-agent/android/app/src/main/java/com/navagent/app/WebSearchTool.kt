package com.navagent.app

import com.navagent.core.LlmTool
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通用 Web 搜索（Tavily）。
 *
 * 为什么是 Tavily：MiniMax 的 API 没有内置联网，只能自己接搜索服务。
 * 实测这台手机能直连 `api.tavily.com`（23.21.204.74，10ms），不需要挂代理。
 *
 * 它和 [NearbyTool] 的分工，靠 description 划清楚：
 *   search_nearby → 地理实体（加油站、餐厅、停车场），走高德 POI，带距离
 *   web_search    → 新闻、事件、价格、政策这类「网上才有」的信息
 * 两个都不管天气（那是 [WeatherTool]）。
 *
 * ★ 2026-11-01 按「搜新闻不给力」重做了一版，新闻和通用走两套参数：
 *
 *   新闻（topic=news）                    通用（topic=general）
 *   search_depth = advanced（basic 只回几十字，模型没法用）
 *   max_results  = 8（多拿几条再筛）
 *   include_answer = false（见下）
 *   默认 time_range = week（不限定会翻出几个月前的旧闻）
 *   结果按发布时间倒序，过滤「图片新闻」那种几十字的条目
 *
 *   为什么要关掉新闻的 include_answer：Tavily 的 answer 是它自己生成的一段摘要，
 *   实测搜「张掖 新闻」返回的是**英文**（"In Zhangye, villagers in Long'an Town..."）。
 *   语音助手把这段念出来就是事故，而且模型看到开头有现成答案，往往不再看下面的来源。
 *   通用查询保留 answer —— 那种场景它确实能省一次阅读。
 */
class WebSearchTool(private val apiKey: () -> String) : LlmTool {

    override val name = "web_search"

    override val description =
        "搜索互联网，查实时信息和事实：新闻、事件进展、价格、政策法规、人物近况、公司资料、" +
            "以及任何你不确定或可能过时的知识。\n" +
            "**问新闻、最近发生的事，topic 必须传 news**；查资料、常识、价格这类用 general。" +
            "新闻按时间范围传 time_range：今天或最近两三天用 day，这周用 week，这个月用 month，" +
            "说不清就不传（默认一周内）。只传 general 会搜到旧文章。\n" +
            "不要用它查本地店铺（加油站/餐厅/停车场用 search_nearby），也不要查天气（用 get_weather）。"

    override fun parametersJson(): String = """
        {"type":"object","properties":{
          "query":{"type":"string","description":"搜索主题或关键词，中文查询尽量保留具体实体和时间"},
          "topic":{"type":"string","enum":["general","news"],"description":"问新闻/近期事件用 news，查资料用 general"},
          "time_range":{"type":"string","enum":["day","week","month","year"],"description":"news 的时间范围，不传默认一周"}
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
        val isNews = topic == "news"

        val key = apiKey().trim()
        if (key.isBlank()) {
            Dbg.log("web_search 未执行：Tavily Key 未配置")
            return "联网搜索不可用：没有配置 Tavily 搜索 Key。请在设置→联网搜索配置 Key；本次没有联网，不能把模型记忆当作实时搜索结果。"
        }

        val payload = JSONObject()
            .put("query", query)
            .put("topic", topic)
            // 新闻要正文：basic 的 content 只有 60~90 字，全是「图片新闻」那种标题党
            .put("search_depth", if (isNews) "advanced" else "basic")
            // 新闻拿 5 条够筛（advanced 每条约 2KB 正文，再多只是拖时间：实测 8 条要 8.6s，6 条约 5s）
            .put("max_results", if (isNews) 5 else 5)
            // 新闻不要 Tavily 的自动摘要：中文查询它常回英文，模型还会照着念
            .put("include_answer", !isNews)
            .put("include_published_date", true)
            // ★ 排除境外敏感媒体。实测搜「国内新闻 今天」返回新唐人电视台的
            //   「古宁头大捷 77 年」「反共战场」—— 模型按 prompt 不该展开这类内容，
            //   结果直接卡死输出 0 字。在工具层就把这些源挡掉，别让模型看见。
            .put("exclude_domains", org.json.JSONArray(SENSITIVE_DOMAINS))
            .apply {
                val range = timeRange ?: if (isNews) "week" else null
                if (range != null) put("time_range", range)
            }
            .toString()

        var conn: HttpURLConnection? = null
        return try {
            val t0 = System.currentTimeMillis()
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                // 新闻用 advanced，服务端要抓正文，给足时间
                readTimeout = if (isNews) 22_000 else 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Dbg.log(
                "web_search($query) topic=$topic range=${timeRange ?: "-"} " +
                    "HTTP $code ${System.currentTimeMillis() - t0}ms"
            )

            if (code !in 200..299) {
                Dbg.log("web_search 失败 ${body.take(200)}")
                return when (code) {
                    401, 403 -> "联网搜索失败：Tavily Key 无效或没有权限；本次没有取得搜索结果。请明确告知用户未能核实实时信息。"
                    429 -> "联网搜索失败：Tavily 额度不足；本次没有取得搜索结果。请明确告知用户未能核实实时信息。"
                    else -> "联网搜索失败（HTTP $code）；本次没有取得搜索结果。请明确告知用户未能核实实时信息，再谨慎提供可能过时的背景知识。"
                }
            }

            val json = JSONObject(body)
            val out = if (isNews) renderNews(json, query) else renderGeneral(json)
            return out.ifBlank { "没搜到「$query」的相关内容" }
        } catch (t: Throwable) {
            Dbg.log("web_search 异常 ${t.javaClass.simpleName}: ${t.message}")
            "搜索出错了：${t.message ?: t.javaClass.simpleName}"
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------ 新闻

    /**
     * 新闻结果的渲染：按发布时间倒序 + 过滤空壳条目。
     *
     * 为什么要过滤：Tavily 对中文新闻会返回一堆「【图片新闻】张掖：村民翻晒辣椒」
     * 这种 60 字条目，正文只有图注。模型拿它当新闻事实会闹笑话，
     * 而且它排在前面会把真正有内容的报道挤掉。
     */
    private fun renderNews(json: JSONObject, query: String): String {
        val results = json.optJSONArray("results") ?: return ""
        data class Item(val title: String, val url: String, val published: Long, val dateText: String, val content: String)

        val all = (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            // 境外敏感源整条丢弃 —— 详见 isBlockedSource 的注释，
            // 不过滤的话整轮请求会被模型服务端 422 拒掉
            if (isBlockedSource(r.optString("url"))) return@mapNotNull null
            val content = r.optString("content").trim()
            val rawTitle = r.optString("title").trim()
            // 乱码标题（GBK 站点被按 UTF-8 解）直接丢掉标题，用域名顶上
            val title = if (rawTitle.contains('\uFFFD') || rawTitle.count { it.code in 0xE000..0xF8FF } > 2) {
                hostOf(r.optString("url"))
            } else rawTitle
            Item(
                title = title.take(90),
                url = r.optString("url"),
                published = parseDate(r.optString("published_date")),
                dateText = r.optString("published_date"),
                content = content
            )
        }

        // 有正文的优先，短条目补位 —— 不能只留「有正文」的，
        // 实测中文新闻大半是转载短讯，过滤太狠会只剩 1 条，模型没得选
        val solid = all.filter { it.content.length >= MIN_NEWS_CONTENT }
            .sortedByDescending { it.published }
        val thin = all.filter { it.content.length < MIN_NEWS_CONTENT }
            .sortedByDescending { it.published }
        val picked = (solid + thin).take(NEWS_MAX_ITEMS)
        if (picked.isEmpty()) return ""

        return buildString {
            append("搜索结果（按发布时间由新到旧）：\n")
            picked.forEach { item ->
                append("- ").append(item.title)
                if (item.url.isNotBlank()) append("（").append(item.url.take(140)).append("）")
                append(" · ").append(freshnessText(item.published, item.dateText))
                append("\n")
                if (item.content.isNotBlank()) {
                    // 320 字够念两句要闻。之前给 500 字，两条新闻加起来就 6000 字，
                    // 模型处理超时（实测 38 秒 SocketTimeout，一个字都没回）
                    append("  ").append(item.content.take(320)).append("\n")
                }
            }
            if (solid.isEmpty()) {
                append("\n注意：以上都是短条目（多为图片新闻或版面导航），细节可能不足。")
            }
        }.trim()
    }

    // ------------------------------------------------------------------ 通用

    private fun renderGeneral(json: JSONObject): String = buildString {
        val answer = json.optString("answer").trim()
        if (answer.isNotEmpty()) append(answer).append("\n\n")

        val results = json.optJSONArray("results") ?: return@buildString
        var appended = 0
        for (i in 0 until results.length()) {
            if (appended >= 3) break
            val r = results.optJSONObject(i) ?: continue
            // 通用搜索同样要过滤：敏感源名一样会让下一轮请求 422
            if (isBlockedSource(r.optString("url"))) continue
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

    // ------------------------------------------------------------------ 工具

    /** Tavily 的时间是 RFC1123（`Fri, 30 Oct 2026 15:00:00 GMT`），解析失败返回 0 */
    private fun parseDate(raw: String): Long {
        if (raw.isBlank() || raw == "null") return 0L
        return runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(raw)?.time ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * 「3 小时前」「2 天前」。
     *
     * 为什么要转相对时间：模型看到 `Fri, 30 Oct 2026 15:00:00 GMT` 没法直观判断新旧，
     * 而「3 小时前」它一眼就知道该不该说「最新」。日期解析不出来就原样给。
     */
    private fun freshnessText(published: Long, raw: String): String {
        if (published <= 0L) return if (raw.isBlank() || raw == "null") "时间未知" else raw
        val diff = System.currentTimeMillis() - published
        return when {
            diff < 0 -> "刚刚"
            diff < 60 * 60 * 1000 -> "${diff / 60000} 分钟前"
            diff < 24 * 60 * 60 * 1000 -> "${diff / 3600000} 小时前"
            diff < 30L * 24 * 60 * 60 * 1000 -> "${diff / (24 * 3600000)} 天前"
            else -> raw
        }
    }

    private fun hostOf(url: String): String =
        runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("（标题乱码）")

    /**
     * 这些源的结果直接丢掉。
     *
     * ★ 2026-11-02 加。根因排查记录：
     *   用户反馈"搜新闻经常失败"。日志里搜索本身全 HTTP 200、最慢 5.9 秒，
     *   失败发生在下一轮 LLM 请求：
     *     `HTTP 422 {"error":{"type":"unprocessable_entity_error",
     *      "message":"input new_sensitive (1026)"}}`
     *
     *   逐条喂给 MiniMax 才定位到：同一批 5 条结果里，前三条（世界新闻网、
     *   央视、江门新闻网）正常返回，第 4 条「新唐人电视台」和第 5 条
     *   「禁闻网」一进去就 422 —— **源名本身就触发输入审核**。
     *
     *   这不是"绕过审核"：这类源本来就不该被当作新闻事实喂给模型。
     *   过滤掉之后既不会 422，也避免模型转述不可靠内容。
     */
    private fun isBlockedSource(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase().removePrefix("www.") }.getOrNull() ?: return false
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private companion object {
        const val ENDPOINT = "https://api.tavily.com/search"

        /** 短于这个长度的新闻正文基本是图注/版面导航，不算内容 */
        const val MIN_NEWS_CONTENT = 120

        /**
         * 境外中文媒体黑名单 —— 命中即整条丢弃，见 [isBlockedSource]。
         *
         * 维护说明：加域名时用根域名（`ntdtv.com`），匹配时会自动带上子域
         * （`www.ntdtv.com` / `m.ntdtv.com` 都会命中）。不要加路径。
         */
        val BLOCKED_HOSTS = listOf(
            "ntdtv.com",          // 新唐人
            "djynews.com",        // 大纪元
            "epochtimes.com",
            "secretchina.com",    // 看中国
            "kanzhongguo.com",
            "bnn.co",             // 禁闻网
            "bannedbook.org",
            "soundofhope.org",    // 希望之声
            "rfa.org",            // 自由亚洲
            "voachinese.com",     // 美国之音
            "aboluowang.com",     // 阿波罗
            "boxun.com",          // 博讯
            "dw.com",             // 德国之声
            "bbc.com",
            "nytimes.com",
            "6park.com",          // 留园
            "wenxuecity.com"      // 文学城
        )

        /** 新闻最多给模型几条。多了占上下文，也拖慢首字 */
        const val NEWS_MAX_ITEMS = 5

        /**
         * 境外敏感媒体黑名单。
         *
         * ★ 为什么在工具层挡：实测搜「国内新闻 今天」，Tavily 第一条给的是新唐人电视台
         *   「古宁头大捷 77 年 / 反共战场」。模型看到这类内容会撞上 system prompt 里
         *   「政治不展开」那条，直接输出 0 字 —— 用户问「今天的新闻」得到一片沉默。
         *   与其让模型纠结，不如根本不让它看见。
         */
        val SENSITIVE_DOMAINS = listOf(
            "ntdtv.com",
            "epochtimes.com",
            "secretchina.com",
            "aboluowang.com",
            "rfa.org",
            "chinadigitaltimes.net",
            "boxun.com"
        )
    }
}
