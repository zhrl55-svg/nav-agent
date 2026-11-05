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
        "搜索互联网，查**会随时间变化**的信息：新闻时事、事件进展、天气之外的价格、" +
            "营业时间、门票、限行、路况拥堵、排队、评分评价、政策法规、人物近况、公司资料、" +
            "当前版本、以及任何你不确定或可能过时的知识。\n" +
            "**问新闻、最近发生的事，topic 必须传 news**；查资料、规定、营业时间这类用 general。" +
            "新闻按时间范围传 time_range：今天或最近两三天用 day，这周用 week，这个月用 month，" +
            "说不清就不传（默认一周内）。只传 general 会搜到旧文章。\n" +
            "搜索词要具体，写「地名 + 主题」（如「张掖 供暖」「兰州 牛肉面 营业时间」），" +
            "别用「近期事件」这种泛词。\n" +
            "**一次搜索结果不够时可以再搜一次**（换关键词或放宽时间范围），最多搜两次；" +
            "也可以同时发两个不同关键词的搜索，比一轮一个更快。\n" +
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
            // ★ 没有 Key 也要能搜。降级链不依赖任何 Key，
            //   实测这台手机直连搜狗/百度/Bing 只要 32~41ms。
            //   原来这里直接返回失败，等于「没配 Tavily = 完全不能联网」，
            //   而实际上国产引擎一直都在。
            Dbg.log("web_search：Tavily Key 未配置，直接走备用搜索")
            return fallback(query, "没有配置 Tavily Key")
        }

        // ★★ 2026-11-04 实测重做的参数策略。原来 news 用 advanced、general 用 basic，
        //   结果「张掖 供暖」搜回来的是 Trip.com 旅行地、日照金山、辽宁高铁 —— 全不相关，
        //   而且 HTTP 200 一切正常。**最坏的失败是看起来成功了，其实没搜到。**
        //
        // 四个改动，每一个都是实测出来的：
        //
        // ① country=china（中文查询自动加）
        //    加之前：Trip.com 旅行地 / 日照金山 / 辽宁高铁
        //    加之后：张掖市权威新闻门户 / 甘州区供暖通知 / 甘肃供热新闻   ← 完全对题
        //    Tavily 的默认检索偏向英文大站，中文本地内容全靠 country 偏置才捞得回来。
        //    ★ 只对**含中日韩字符**的查询加；实测英文查询「Tesla latest news」
        //      加 country=china 后结果变窄（少了 BASENOR 那种聚合站），不加更全。
        //
        // ② search_depth=advanced 全用（general 也不例外）
        //    实测 general 用 basic 时 content 只有 80~100 字，
        //    换成 advanced 后同样的查询拿到 300~2000 字的正文。
        //    原来「general 用 basic 省时间」是误判 —— basic 省的那点时间，
        //    换来的是模型只能看到标题然后去编。
        //
        // ③ include_raw_content=markdown
        //    实测 basic/advanced 的 content 有时只是几十字的摘要，
        //    raw_content 才是真的网页正文（张掖供热通知拿回 473 字正文）。
        //    模型有正文才不会去猜。
        //
        // ④ days 替代 time_range（仅 news）
        //    days 是天数（1~30），比 time_range 的 day/week/month 粒度细。
        //    实测 days=3 和 days=7 结果明显不同 —— 「今天的事」用 week 会混进一周前的。
        val isChinese = query.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
        val payload = JSONObject()
            .put("query", query)
            .put("topic", topic)
            .put("search_depth", "advanced")
            .put("max_results", if (isNews) 6 else 5)
            // 新闻不要 Tavily 的自动摘要：中文查询它常回英文，模型还会照着念
            .put("include_answer", !isNews)
            .put("include_published_date", true)
            // ★ 拿真正文：content 有时只是几十字摘要，raw_content 才是网页正文
            .put("include_raw_content", "markdown")
            // ★ 排除境外敏感媒体。实测搜「国内新闻 今天」返回新唐人电视台的
            //   「古宁大头捷 77 年」「反共战场」—— 模型按 prompt 不该展开这类内容，
            //   结果直接卡死输出 0 字。在工具层就把这些源挡掉，别让模型看见。
            .put("exclude_domains", org.json.JSONArray(SENSITIVE_DOMAINS))
            .apply {
                if (isChinese) put("country", "china")
                if (isNews) {
                    // 天数优先用模型给的 time_range 换算，其次沿用一周
                    val days = when (timeRange) {
                        "day" -> 1
                        "week" -> 7
                        "month" -> 30
                        "year" -> null
                        else -> 7
                    }
                    if (days != null) put("days", days) else put("time_range", "year")
                }
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
                // ★★ Tavily 任何一种失败都不该直接终结 —— 退到国内引擎再试一次。
                //   实测这台手机上搜狗/百度/Bing 全部直连可达（32~41ms），
                //   Tavily 挂了不该等于「搜不到」。
                val reason = when (code) {
                    401, 403 -> "Tavily Key 无效或没有权限"
                    429 -> "Tavily 额度不足"
                    else -> "Tavily 返回 HTTP $code"
                }
                return fallback(query, reason)
            }

            val json = JSONObject(body)
            val out = if (isNews) renderNews(json, query) else renderGeneral(json)
            if (out.isNotBlank()) return out

            // ★ Tavily 返回了 200 但结果为空 / 全被过滤掉 —— 这也是失败。
            //   实测「张掖 供暖」就会这样：返回一堆不相关的旅行地，
            //   过滤后一条不剩。静默返回「没搜到」不如换个引擎再试。
            Dbg.log("web_search 结果为空或不相关，降级")
            return fallback(query, "Tavily 没返回可用结果")
        } catch (t: Throwable) {
            Dbg.log("web_search 异常 ${t.javaClass.simpleName}: ${t.message}")
            // 网络异常同样降级 —— Tavily 超时不代表国内引擎也超时
            fallback(query, "Tavily 连接失败（${t.javaClass.simpleName}）")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 降级到国内搜索引擎。
     *
     * @param reason Tavily 失败的原因，会写进给模型看的说明里
     * @return 给模型的结果文本
     */
    private fun fallback(query: String, reason: String): String {
        val t0 = System.currentTimeMillis()
        val result = FallbackWebSearch.search(query)
        if (result == null) {
            Dbg.log("降级搜索也失败：全部引擎无结果 ${System.currentTimeMillis() - t0}ms")
            return "联网搜索失败：$reason，并且备用搜索也没有取得结果。" +
                "本次没有取得任何实时来源，请明确告知用户未能核实实时信息，" +
                "再谨慎提供可能过时的背景知识。"
        }
        Dbg.log("降级搜索成功 ${result.engine} ${System.currentTimeMillis() - t0}ms")
        return buildString {
            append("（主搜索服务不可用：").append(reason).append("；已改用备用搜索。）\n")
            append(FallbackWebSearch.render(result, query))
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
            // ★ raw_content 才是真网页正文，content 有时只是几十字摘要。
            //   实测「张掖 供暖」带 raw_content 时拿回 473~2000 字正文，
            //   不带只有 78~100 字。模型有正文才不会去编。
            val content = cleanBody(r.optString("raw_content").trim()
                .ifBlank { r.optString("content").trim() })
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
        //
        // ★ 导航页过滤：正文大半是站点导航（首页/登录/招标/图片）时，
        //   这条对模型没有任何信息量，只会把它带偏。
        //   实测「石家庄…管廊监理终止公告」整条正文全是「[首页] [招标] 会员登录」。
        //   判定：导航特征词占的比例过高就降级成 thin（排到最后）。
        val solid = all.filter { it.content.length >= MIN_NEWS_CONTENT && !isNavNoise(it.content) }
            .sortedByDescending { it.published }
        val thin = all.filter { it.content.length < MIN_NEWS_CONTENT || isNavNoise(it.content) }
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
            // 同上：raw_content 优先，它是真正文
            val content = cleanBody(r.optString("raw_content").trim()
                .ifBlank { r.optString("content").trim() })
                .ifBlank { r.optString("content").trim() }
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

    /**
     * 清洗正文。
     *
     * ★ 为什么必须洗：raw_content 是 markdown 格式的网页正文，
     *   实测拿到的是这样的东西：
     *     ![](https://x0.ifengimg.com/ucms/2025_29/DB9A...png)
     *     * [首页](/ "建设工程招标网")
     *     * [招标](/invite.html "建设工程招标网")
     *     会员登录 ![](/images/arrowBottom.png)
     *
     *   这些图片和站点导航占了正文一大半，真正有用的那句话反而被挤到中间。
     *   不洗的话模型读到的全是导航菜单，答出来就是「该网站提供招标信息服务」。
     *
     * 洗掉的：markdown 图片、链接的 URL 部分、残留的 HTML 标签、空行。
     * 保留的：正文文字、链接的可读文字（标题常常就在里面）。
     */
    /**
     * 判断正文是不是站点导航/页面框架而不是真内容。
     *
     * 判据：导航特征词出现的次数，占正文行数的比例过高就判定为导航。
     * 真实新闻正文里几乎不会出现「会员登录」「版权所有」「友情链接」这类词，
     * 而一个导航页几乎全是这些词。
     */
    /**
     * 判断正文是不是站点导航/页面框架而不是真内容。
     *
     * 两个判据（任一命中就判定为导航页）：
     *
     * ① 导航特征词密度过高 —— 命中数达到非空行数的一半。
     *    真实新闻正文里几乎不会出现「会员登录」「版权所有」「友情链接」，
     *    而一个导航页几乎全是这些词。
     *
     * ② 没有任何一段像正文的长句 —— 所有非空行都很短（< 12 字）。
     *    「首页 / 招标 / 项目 / 会员登录」这种导航菜单每行就两三个字，
     *    而真正文一定有像「每年交采暖费，最闹心的一件事…」这样的长句。
     *    ★ 这个判据比特征词密度更准：实测那条石家庄招标公告
     *    导航词只占小半（后面还有「发布日期」「所属地区」这些表单字段），
     *    但正文段落全是短行，靠特征词密度抓不住。
     */
    /**
     * 判断正文是不是站点导航/页面框架而不是真内容。
     *
     * 三个判据（任一命中就判定为导航页）：
     *
     * ① 导航特征词密度过高 —— 命中数达到非空行数的一半。
     *    真实新闻正文里几乎不会出现「会员登录」「版权所有」「友情链接」，
     *    而一个导航页几乎全是这些词。
     *
     * ② 短行占比过高 —— 超过一半的非空行都不到 12 个字。
     *    导航菜单每行两三个字（首页 / 招标 / 会员中心），
     *    真正文一定有连续十几个字以上的句子。
     *
     * ③ 「有导航词」且「短行多」同时成立。
     *    ★ 只看长行会被表单字段骗过：那条石家庄招标公告里有
     *    「发布日期：2026-10-30」「所属地区：河北省 石家庄市」这样的长行，
     *    但整篇仍然是页面框架。两个条件同时看才抓得住。
     */
    private fun isNavNoise(content: String): Boolean {
        if (content.length < MIN_NEWS_CONTENT) return false
        val nonEmpty = content.lines().filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return true
        val hits = NAV_MARKERS.count { content.contains(it) }
        if (hits * 2 >= nonEmpty.size) return true
        val shortLines = nonEmpty.count { it.length < LONG_LINE_CHARS }
        val shortRatio = shortLines * 2 >= nonEmpty.size
        // 导航词 + 短行占多数 → 页面框架
        if (hits > 0 && shortRatio) return true
        // 一行长句都没有 → 纯菜单
        return nonEmpty.none { it.length >= LONG_LINE_CHARS }
    }
    /**
     * 清洗正文。
     *
     * ★ 为什么必须洗：raw_content 是 markdown 格式的网页正文，
     *   实测拿到的是这样的东西：
     *     ![](https://x0.ifengimg.com/ucms/2025_29/DB9A...png)
     *     * [首页](/ "建设工程招标网")
     *     * [招标](/invite.html "建设工程招标网")
     *     会员登录 ![](/images/arrowBottom.png)
     *
     *   这些图片和站点导航占了正文一大半，真正有用的那句话反而被挤到中间。
     *   不洗的话模型读到的全是导航菜单，答出来就是「该网站提供招标信息服务」。
     *
     * 洗掉的：markdown 图片、链接的 URL 部分、残留的 HTML 标签、空行。
     * 保留的：正文文字、链接的可读文字（标题常常就在里面）。
     */
    private fun cleanBody(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw
        // markdown 图片：![alt](url) → 删掉（alt 常常是空）
        s = s.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")
        // markdown 链接：[text](url) → text
        s = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        // 残留 HTML 标签
        s = s.replace(Regex("<[^>]{1,80}>"), " ")
        // 行内 markdown 强调符号
        s = s.replace(Regex("[*_`]{1,3}"), "")
        // 压缩空白，但保留段落换行（模型按段落读更自然）
        s = s.replace(Regex("[ \\t\\u00A0]+"), " ")
        s = s.replace(Regex("(\\s*\\n\\s*){3,}"), "\n\n")
        return s.trim()
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
        /**
         * 正文里「像正文」的最小行长（字）。
         *
         * 导航菜单每行两三个字（首页 / 招标 / 会员中心），
         * 真正文一定有连续十几个字以上的句子。
         */
        const val LONG_LINE_CHARS = 20

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
        /**
         * 站点导航特征词。用来识别「抓到的其实是页面框架，不是文章正文」。
         *
         * 命中数达到正文行数的一半 → 判定为导航页。
         */
        val NAV_MARKERS = listOf(
            "会员登录", "登录", "注册", "首页", "版权所有", "友情链接", "网站地图",
            "联系我们", "关于我们", "广告", "订阅", "上一篇", "下一篇", "免责声明",
            "更多精彩", "热门推荐", "相关推荐", "点击查看"
        )

        const val NEWS_MAX_ITEMS = 6

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
